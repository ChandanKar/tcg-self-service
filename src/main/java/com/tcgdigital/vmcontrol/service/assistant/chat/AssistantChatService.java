package com.tcgdigital.vmcontrol.service.assistant.chat;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.assistant.AssistantTool;
import com.tcgdigital.vmcontrol.service.assistant.AssistantToolGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-app Ask panel's assistant (E36-T05): sends the question, up to 10 earlier turns and the
 * read-only assistant tools to the configured model, runs the tools it asks for through
 * {@link AssistantToolGuard} as the signed-in user (so it sees only their environments, within the
 * same limits and audit), and returns the final answer with the tools used. At most 6 tool rounds
 * per question and a daily number of questions per user. Nothing is kept beyond the audit rows.
 */
@Service
public class AssistantChatService {

    static final int MAX_TOOL_ROUNDS = 6;
    static final int MAX_HISTORY_TURNS = 10;
    public static final int MAX_QUESTION_CHARS = 2000;
    static final int MAX_HISTORY_CHARS = 4000;

    static final String SYSTEM_PROMPT = """
            You are the assistant of a VM self-service platform where teams start, stop and schedule cloud \
            environments (groups of EC2 instances, EKS node groups and RDS databases) and follow their cost.
            You answer the signed-in user's questions about the environments they can see: environments, \
            costs, savings, idle resources and rightsizing. Use the tools to look things up.
            Rules:
            - Answer only from tool results. If the tools do not give the answer, say so; never guess numbers.
            - Say which figures are estimated (the platform's static-pricing estimates) and which are actual \
            (billed costs from Cost Explorer). Amounts are in USD.
            - You cannot start, stop or change anything; tell the user where in the platform to do it instead.
            - Be brief. Write plain text: short paragraphs and lists whose lines start with "- ". No HTML, \
            no Markdown tables.""";

    /** One earlier turn from the panel: role "user" or "assistant". */
    public record HistoryTurn(String role, String text) {
    }

    public record ToolUse(String name, Map<String, Object> arguments, boolean error) {
    }

    public record AskResult(String answer, List<ToolUse> toolCalls, boolean incomplete) {
    }

    /** The user has asked their questions for today. */
    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(int limit) {
            super("You have asked " + limit + " questions today, the daily limit. Try again tomorrow.");
        }
    }

    private final Map<String, AssistantTool> tools = new LinkedHashMap<>();
    private final AssistantToolGuard guard;
    private final Map<String, LlmClient> clients = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, long[]> quota = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Value("${assistant.chat.enabled:false}")
    private boolean enabled;

    @Value("${assistant.chat.provider:anthropic}")
    private String provider = "anthropic";

    @Value("${assistant.chat.provider-url:https://api.anthropic.com}")
    private String providerUrl = "https://api.anthropic.com";

    @Value("${assistant.chat.model:claude-opus-5-5}")
    private String model = "claude-opus-5-5";

    @Value("${assistant.chat.api-key:}")
    private String apiKey = "";

    @Value("${assistant.chat.max-tokens:2048}")
    private int maxTokens = 2048;

    @Value("${assistant.chat.daily-questions-per-user:50}")
    private int dailyQuestionsPerUser = 50;

    public AssistantChatService(List<AssistantTool> tools, AssistantToolGuard guard, List<LlmClient> clients) {
        tools.stream().sorted((a, b) -> a.name().compareTo(b.name())).forEach(t -> this.tools.put(t.name(), t));
        this.guard = guard;
        clients.forEach(c -> this.clients.put(c.provider(), c));
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** On, with a key and a known provider; otherwise the panel and its API do not exist. */
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank() && clients.containsKey(provider);
    }

    public int dailyLimit() {
        return dailyQuestionsPerUser;
    }

    public int remainingToday(String userId) {
        long[] used = quota.get(userId);
        long today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay();
        return used == null || used[0] != today ? dailyQuestionsPerUser : (int) Math.max(0, dailyQuestionsPerUser - used[1]);
    }

    /** Answers one question as {@code user}, who must be the signed-in user (the tools read the security context). */
    public AskResult ask(User user, String question, List<HistoryTurn> history) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Ask a question");
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("Questions are at most " + MAX_QUESTION_CHARS + " characters");
        }
        takeQuota(user.getUserId());

        LlmClient client = clients.get(provider);
        List<LlmClient.ToolSpec> specs = tools.values().stream()
                .map(t -> new LlmClient.ToolSpec(t.name(), t.description(), t.inputSchema())).toList();
        List<LlmClient.Turn> turns = new ArrayList<>(history(history));
        turns.add(new LlmClient.UserText(question.trim()));
        List<ToolUse> used = new ArrayList<>();

        for (int round = 0; ; round++) {
            LlmClient.Reply reply = client.complete(new LlmClient.Request(providerUrl, apiKey, model, maxTokens, SYSTEM_PROMPT,
                    turns, specs));
            if (reply.toolCalls().isEmpty()) {
                return new AskResult(reply.text().isBlank() ? "I have no answer to that." : reply.text(), used, false);
            }
            if (round >= MAX_TOOL_ROUNDS) {
                String partial = reply.text().isBlank() ? "" : reply.text() + "\n\n";
                return new AskResult(partial + "I could not finish within " + MAX_TOOL_ROUNDS
                        + " rounds of lookups. Try a narrower question, for example about one environment.", used, true);
            }
            turns.add(new LlmClient.AssistantReply(reply.text(), reply.toolCalls()));
            List<LlmClient.ToolResult> results = new ArrayList<>();
            for (LlmClient.ToolCall call : reply.toolCalls()) {
                AssistantTool tool = tools.get(call.name());
                if (tool == null) {
                    used.add(new ToolUse(call.name(), call.arguments(), true));
                    results.add(new LlmClient.ToolResult(call.id(), "Unknown tool: " + call.name(), true));
                    continue;
                }
                AssistantToolGuard.Outcome outcome = guard.invoke(user.getUserId(), null, tool, call.arguments());
                used.add(new ToolUse(call.name(), call.arguments(), outcome.error()));
                results.add(new LlmClient.ToolResult(call.id(), outcome.text(), outcome.error()));
            }
            turns.add(new LlmClient.ToolResults(results));
        }
    }

    private void takeQuota(String userId) {
        long today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay();
        long[] used = quota.compute(userId, (k, v) -> {
            if (v == null || v[0] != today) return new long[]{today, 1};
            v[1]++;
            return v;
        });
        if (used[1] > dailyQuestionsPerUser) {
            throw new QuotaExceededException(dailyQuestionsPerUser);
        }
    }

    /** The last 10 earlier turns, starting with the user's, consecutive turns of one role joined. */
    static List<LlmClient.Turn> history(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<HistoryTurn> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY_TURNS), history.size());
        List<String[]> merged = new ArrayList<>();
        for (HistoryTurn turn : recent) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) continue;
            String role = "assistant".equals(turn.role()) ? "assistant" : "user";
            String text = turn.text().length() > MAX_HISTORY_CHARS ? turn.text().substring(0, MAX_HISTORY_CHARS) : turn.text();
            if (merged.isEmpty() && role.equals("assistant")) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[0].equals(role)) {
                merged.get(merged.size() - 1)[1] += "\n\n" + text;
            } else {
                merged.add(new String[]{role, text});
            }
        }
        // The new question is the user's turn, so the history must end with the assistant's.
        if (!merged.isEmpty() && merged.get(merged.size() - 1)[0].equals("user")) merged.remove(merged.size() - 1);
        return merged.stream().<LlmClient.Turn>map(m -> m[0].equals("user")
                ? new LlmClient.UserText(m[1]) : new LlmClient.AssistantReply(m[1], List.of())).toList();
    }
}
