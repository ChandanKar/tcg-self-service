package com.tcgdigital.vmcontrol.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.service.AuditService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every assistant tool call goes through here (E36-T03): a per-user limit of calls a minute, a
 * timeout, a cap on the output's size and an audit row with the tool, the arguments (long text
 * shortened), the rows returned and the time taken. A failed audit write never fails the call.
 * Each tool runs on a worker thread, as the caller, inside a read-only transaction.
 */
@Component
public class AssistantToolGuard {

    private static final Logger log = LoggerFactory.getLogger(AssistantToolGuard.class);
    /** Longer argument text is shortened in the audit row. */
    static final int MAX_ARGUMENT_CHARS = 200;

    /** What a call produced: the text for the client, the structured value when it was not cut, and why it failed. */
    public record Outcome(String text, Object structured, boolean error, boolean truncated, Long retryAfterSeconds) {
        public boolean rateLimited() {
            return retryAfterSeconds != null;
        }
    }

    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final TransactionTemplate readOnly;
    private Clock clock = Clock.systemUTC();

    @Value("${assistant.mcp.rate-limit-per-minute:60}")
    private int rateLimitPerMinute = 60;

    @Value("${assistant.mcp.max-output-chars:50000}")
    private int maxOutputChars = 50_000;

    @Value("${assistant.mcp.tool-timeout-ms:10000}")
    private long toolTimeoutMs = 10_000;

    public AssistantToolGuard(AuditService auditService, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "assistant-tool-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Runs one tool as the current user, whose security context the worker thread takes over. */
    public Outcome invoke(String userId, String tokenPrefix, AssistantTool tool, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        long retryAfter = rateLimit(userId);
        if (retryAfter != 0) {
            if (retryAfter == FIRST_REJECTION) {
                audit(userId, tokenPrefix, tool.name(), args, null, 0, "rate limited");
            }
            long seconds = secondsToNextMinute();
            return new Outcome("Rate limited: at most " + rateLimitPerMinute + " tool calls a minute; try again in "
                    + seconds + " s", null, true, false, seconds);
        }
        long started = clock.millis();
        Object value;
        try {
            Future<Object> future = executor.submit(new DelegatingSecurityContextCallable<>(() -> readOnly.execute(status -> tool.call(args))));
            try {
                value = future.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return failed(userId, tokenPrefix, tool, args, started, "The tool took too long; ask for less", "timed out");
            }
        } catch (RejectedExecutionException e) {
            return failed(userId, tokenPrefix, tool, args, started, "The assistant is busy; try again shortly", "busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(userId, tokenPrefix, tool, args, started, "The tool failed", "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException || cause instanceof ValidationException
                    || cause instanceof ResourceNotFoundException || cause instanceof UnauthorizedException) {
                String message = cause.getMessage() == null ? "Refused" : cause.getMessage();
                return failed(userId, tokenPrefix, tool, args, started, message, message);
            }
            log.warn("Assistant tool {} failed: {}", tool.name(), cause == null ? e.getMessage() : cause.toString());
            return failed(userId, tokenPrefix, tool, args, started, "The tool failed", "failed");
        }

        String text;
        try {
            text = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return failed(userId, tokenPrefix, tool, args, started, "The tool failed", "unserialisable result");
        }
        boolean truncated = text.length() > maxOutputChars;
        if (truncated) {
            text = text.substring(0, maxOutputChars);
        }
        audit(userId, tokenPrefix, tool.name(), args, rows(value), clock.millis() - started, truncated ? "truncated" : null);
        return new Outcome(text, truncated || !(value instanceof Map<?, ?>) ? null : value, false, truncated, null);
    }

    private Outcome failed(String userId, String tokenPrefix, AssistantTool tool, Map<String, Object> args, long started,
                           String message, String reason) {
        audit(userId, tokenPrefix, tool.name(), args, null, clock.millis() - started, reason);
        return new Outcome(message, null, true, false, null);
    }

    private static final long FIRST_REJECTION = -1;

    /** 0 when the call may go ahead, {@link #FIRST_REJECTION} for the first refusal in a minute, else 1. */
    private long rateLimit(String userId) {
        long minute = clock.millis() / 60_000L;
        long[] window = windows.compute(userId == null ? "" : userId, (k, w) -> {
            if (w == null || w[0] != minute) return new long[]{minute, 1};
            w[1]++;
            return w;
        });
        if (window[1] <= rateLimitPerMinute) return 0;
        return window[1] == rateLimitPerMinute + 1L ? FIRST_REJECTION : 1;
    }

    private long secondsToNextMinute() {
        long now = clock.millis();
        return Math.max(1, ((now / 60_000L + 1) * 60_000L - now + 999) / 1000);
    }

    /** The size of the largest list in a result, which is what the tool returned rows of. */
    static Integer rows(Object value) {
        if (value instanceof Collection<?> c) return c.size();
        if (!(value instanceof Map<?, ?> m)) return null;
        Integer most = null;
        for (Object v : m.values()) {
            if (v instanceof Collection<?> c && (most == null || c.size() > most)) most = c.size();
        }
        return most;
    }

    /** The arguments as key=value, each value at most 200 characters. */
    static String summarise(Map<String, Object> args) {
        StringJoiner joiner = new StringJoiner(", ");
        args.forEach((k, v) -> {
            String s = String.valueOf(v);
            joiner.add(k + "=" + (s.length() > MAX_ARGUMENT_CHARS ? s.substring(0, MAX_ARGUMENT_CHARS) + "…" : s));
        });
        return joiner.toString();
    }

    private void audit(String userId, String tokenPrefix, String toolName, Map<String, Object> args, Integer rows,
                       long millis, String failure) {
        try {
            String details = "Token " + tokenPrefix + "; args {" + summarise(args) + "}"
                    + (rows == null ? "" : "; rows " + rows) + "; " + millis + " ms";
            if (details.length() > 1000) details = details.substring(0, 1000);
            if (failure == null || "truncated".equals(failure)) {
                auditService.logAction(userId, AuditAction.ASSISTANT_TOOL_CALLED, "ASSISTANT_TOOL", toolName, toolName,
                        failure == null ? details : details + "; output truncated");
            } else {
                auditService.logFailure(userId, AuditAction.ASSISTANT_TOOL_CALLED, "ASSISTANT_TOOL", toolName, toolName,
                        (failure.length() > 200 ? failure.substring(0, 200) : failure) + " (" + details + ")");
            }
        } catch (Exception e) {
            log.warn("Could not audit assistant tool {}: {}", toolName, e.getMessage());
        }
    }
}
