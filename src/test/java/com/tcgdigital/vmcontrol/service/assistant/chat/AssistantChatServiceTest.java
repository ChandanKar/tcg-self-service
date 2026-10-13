package com.tcgdigital.vmcontrol.service.assistant.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.assistant.AssistantTool;
import com.tcgdigital.vmcontrol.service.assistant.AssistantToolGuard;
import com.tcgdigital.vmcontrol.service.assistant.SimpleTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The Ask panel's tool loop (E36-T05) with a stubbed model. */
class AssistantChatServiceTest {

    /** A model that answers from a script and remembers every request. */
    static class ScriptedClient implements LlmClient {
        final List<Request> requests = new ArrayList<>();
        final Function<Integer, Reply> script;

        ScriptedClient(Function<Integer, Reply> script) {
            this.script = script;
        }

        @Override
        public String provider() {
            return "anthropic";
        }

        @Override
        public Reply complete(Request request) {
            requests.add(new Request(request.endpoint(), request.apiKey(), request.model(), request.maxTokens(), request.system(),
                    List.copyOf(request.turns()), request.tools()));
            return script.apply(requests.size());
        }
    }

    private final AuditService auditService = mock(AuditService.class);
    private AssistantToolGuard guard;
    private User user;

    private final AssistantTool whoami = new SimpleTool("whoami", "who", Map.of("type", "object"),
            args -> Map.of("caller", SecurityContextHolder.getContext().getAuthentication().getName()));

    private AssistantChatService service(LlmClient client) {
        AssistantChatService s = new AssistantChatService(List.of(whoami), guard, List.of(client));
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "apiKey", "test-key");
        return s;
    }

    @BeforeEach
    void setUp() {
        guard = new AssistantToolGuard(auditService, new ObjectMapper(), mock(PlatformTransactionManager.class));
        user = new User();
        user.setUserId("u-1");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u-1", "", "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theModelsToolCallRunsAsTheCallerAndItsResultGoesBack() {
        ScriptedClient client = new ScriptedClient(n -> n == 1
                ? new LlmClient.Reply("Let me check.", List.of(new LlmClient.ToolCall("call-1", "whoami", Map.of())))
                : new LlmClient.Reply("You are u-1.", List.of()));

        AssistantChatService.AskResult result = service(client).ask(user, "Who am I?", List.of());

        assertThat(result.answer()).isEqualTo("You are u-1.");
        assertThat(result.incomplete()).isFalse();
        assertThat(result.toolCalls()).extracting(AssistantChatService.ToolUse::name).containsExactly("whoami");
        LlmClient.Request second = client.requests.get(1);
        assertThat(second.turns()).hasSize(3);
        LlmClient.ToolResults results = (LlmClient.ToolResults) second.turns().get(2);
        assertThat(results.results().get(0).callId()).isEqualTo("call-1");
        assertThat(results.results().get(0).text()).contains("\"caller\":\"u-1\"");
        assertThat(second.tools()).extracting(LlmClient.ToolSpec::name).containsExactly("whoami");
        assertThat(second.system()).contains("Answer only from tool results");
        assertThat(second.apiKey()).isEqualTo("test-key");
    }

    @Test
    void anUnknownToolIsReportedToTheModelAsAnError() {
        ScriptedClient client = new ScriptedClient(n -> n == 1
                ? new LlmClient.Reply("", List.of(new LlmClient.ToolCall("c", "stop_everything", Map.of())))
                : new LlmClient.Reply("I cannot do that.", List.of()));

        AssistantChatService.AskResult result = service(client).ask(user, "Stop it all", null);

        assertThat(result.toolCalls()).singleElement().satisfies(t -> assertThat(t.error()).isTrue());
        LlmClient.ToolResults results = (LlmClient.ToolResults) client.requests.get(1).turns().get(2);
        assertThat(results.results().get(0).error()).isTrue();
    }

    @Test
    void theLoopStopsAfterSixToolRounds() {
        ScriptedClient client = new ScriptedClient(n -> new LlmClient.Reply("", List.of(new LlmClient.ToolCall("c" + n, "whoami", Map.of()))));

        AssistantChatService.AskResult result = service(client).ask(user, "Loop forever", List.of());

        assertThat(result.incomplete()).isTrue();
        assertThat(result.answer()).contains("could not finish");
        assertThat(result.toolCalls()).hasSize(AssistantChatService.MAX_TOOL_ROUNDS);
        assertThat(client.requests).hasSize(AssistantChatService.MAX_TOOL_ROUNDS + 1);
    }

    @Test
    void theDailyQuotaIsPerUserAndResetsTheNextDay() {
        AssistantChatService s = service(new ScriptedClient(n -> new LlmClient.Reply("ok", List.of())));
        ReflectionTestUtils.setField(s, "dailyQuestionsPerUser", 2);
        s.setClock(Clock.fixed(Instant.parse("2026-10-13T23:00:00Z"), ZoneOffset.UTC));

        s.ask(user, "one", List.of());
        s.ask(user, "two", List.of());
        assertThat(s.remainingToday("u-1")).isZero();
        assertThatThrownBy(() -> s.ask(user, "three", List.of())).isInstanceOf(AssistantChatService.QuotaExceededException.class);

        s.setClock(Clock.fixed(Instant.parse("2026-10-14T00:30:00Z"), ZoneOffset.UTC));
        assertThat(s.ask(user, "next day", List.of()).answer()).isEqualTo("ok");
    }

    @Test
    void questionsAreCheckedAndHistoryIsTrimmedToAlternatingTurns() {
        AssistantChatService s = service(new ScriptedClient(n -> new LlmClient.Reply("ok", List.of())));
        assertThatThrownBy(() -> s.ask(user, " ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.ask(user, "x".repeat(2001), List.of())).isInstanceOf(IllegalArgumentException.class);

        List<LlmClient.Turn> turns = AssistantChatService.history(List.of(
                new AssistantChatService.HistoryTurn("assistant", "hello"),
                new AssistantChatService.HistoryTurn("user", "a"),
                new AssistantChatService.HistoryTurn("user", "b"),
                new AssistantChatService.HistoryTurn("assistant", "c"),
                new AssistantChatService.HistoryTurn("user", "unanswered")));

        assertThat(turns).hasSize(2);
        assertThat(((LlmClient.UserText) turns.get(0)).text()).isEqualTo("a\n\nb");
        assertThat(((LlmClient.AssistantReply) turns.get(1)).text()).isEqualTo("c");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothProvidersCarryToolCallsAndResults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LlmClient.Request request = new LlmClient.Request("https://x", "k", "m", 100, "sys", List.of(
                new LlmClient.UserText("q"),
                new LlmClient.AssistantReply("", List.of(new LlmClient.ToolCall("t1", "whoami", Map.of("a", 1)))),
                new LlmClient.ToolResults(List.of(new LlmClient.ToolResult("t1", "{}", false)))),
                List.of(new LlmClient.ToolSpec("whoami", "who", Map.of("type", "object"))));

        Map<String, Object> anthropic = AnthropicLlmClient.body(request);
        assertThat(mapper.writeValueAsString(anthropic)).contains("\"system\":\"sys\"", "\"type\":\"tool_use\"",
                "\"tool_use_id\":\"t1\"", "\"input_schema\"").doesNotContain("\"type\":\"text\",\"text\":\"\"");
        LlmClient.Reply a = AnthropicLlmClient.parse(mapper.readTree(
                "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"tool_use\",\"id\":\"u\",\"name\":\"whoami\",\"input\":{\"x\":2}}]}"), mapper);
        assertThat(a.text()).isEqualTo("hi");
        assertThat(a.toolCalls()).singleElement().satisfies(c -> assertThat(c.arguments()).containsEntry("x", 2));

        String openai = mapper.writeValueAsString(OpenAiCompatibleLlmClient.body(request, mapper));
        assertThat(openai).contains("\"role\":\"system\"", "\"tool_calls\"", "\"arguments\":\"{\\\"a\\\":1}\"",
                "\"role\":\"tool\"", "\"tool_call_id\":\"t1\"", "\"parameters\"");
        LlmClient.Reply o = OpenAiCompatibleLlmClient.parse(mapper.readTree(
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"whoami\",\"arguments\":\"{\\\"y\\\":3}\"}}]}}]}"), mapper);
        assertThat(o.text()).isEmpty();
        assertThat(o.toolCalls()).singleElement().satisfies(c -> assertThat(c.arguments()).containsEntry("y", 3));
    }
}
