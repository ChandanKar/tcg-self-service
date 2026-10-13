package com.tcgdigital.vmcontrol.service.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The guard around assistant tool calls (E36-T03): audit, rate limit, timeout and output cap. */
class AssistantToolGuardTest {

    private final AuditService auditService = mock(AuditService.class);
    private AssistantToolGuard guard;

    private static AssistantTool tool(String name, java.util.function.Function<Map<String, Object>, Object> body) {
        return new SimpleTool(name, "test", Map.of("type", "object"), body);
    }

    @BeforeEach
    void setUp() {
        guard = new AssistantToolGuard(auditService, new ObjectMapper(), mock(org.springframework.transaction.PlatformTransactionManager.class));
        guard.setClock(Clock.fixed(Instant.parse("2026-10-13T10:00:30Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u-1", "", "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        guard.shutdown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCallIsAuditedWithTheToolArgumentsRowsAndToken() {
        AssistantToolGuard.Outcome outcome = guard.invoke("u-1", "AbCd1234",
                tool("list_environments", a -> Map.of("environments", List.of(1, 2, 3))), Map.of("search", "pay"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).isEqualTo("{\"environments\":[1,2,3]}");
        assertThat(outcome.structured()).isEqualTo(Map.of("environments", List.of(1, 2, 3)));
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).logAction(eq("u-1"), eq(AuditAction.ASSISTANT_TOOL_CALLED), eq("ASSISTANT_TOOL"),
                eq("list_environments"), eq("list_environments"), details.capture());
        assertThat(details.getValue()).contains("Token AbCd1234", "search=pay", "rows 3");
    }

    @Test
    void theToolRunsAsTheCaller() {
        AtomicReference<String> seen = new AtomicReference<>();
        guard.invoke("u-1", "p", tool("whoami", a -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return Map.of();
        }), Map.of());

        assertThat(seen.get()).isEqualTo("u-1");
    }

    @Test
    void theSixtyFirstCallInAMinuteIsRateLimitedAndAuditedOnce() {
        AssistantTool ping = tool("whoami", a -> Map.of("ok", true));
        for (int i = 0; i < 60; i++) {
            assertThat(guard.invoke("u-1", "p", ping, Map.of()).rateLimited()).isFalse();
        }

        AssistantToolGuard.Outcome limited = guard.invoke("u-1", "p", ping, Map.of());
        guard.invoke("u-1", "p", ping, Map.of());

        assertThat(limited.rateLimited()).isTrue();
        assertThat(limited.retryAfterSeconds()).isEqualTo(30);
        assertThat(limited.text()).contains("Rate limited");
        verify(auditService, times(1)).logFailure(eq("u-1"), eq(AuditAction.ASSISTANT_TOOL_CALLED), anyString(), eq("whoami"),
                anyString(), any());
        // Another user has their own allowance.
        assertThat(guard.invoke("u-2", "p", ping, Map.of()).rateLimited()).isFalse();
        // The next minute starts afresh.
        guard.setClock(Clock.fixed(Instant.parse("2026-10-13T10:01:00Z"), ZoneOffset.UTC));
        assertThat(guard.invoke("u-1", "p", ping, Map.of()).rateLimited()).isFalse();
    }

    @Test
    void longOutputIsCutAndMarkedTruncated() {
        ReflectionTestUtils.setField(guard, "maxOutputChars", 100);

        AssistantToolGuard.Outcome outcome = guard.invoke("u-1", "p", tool("spend_by", a -> Map.of("rows", "x".repeat(500))), Map.of());

        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.text()).hasSize(100);
        assertThat(outcome.structured()).isNull();
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).logAction(any(), any(), any(), any(), any(), details.capture());
        assertThat(details.getValue()).contains("output truncated");
    }

    @Test
    void aSlowToolTimesOut() {
        ReflectionTestUtils.setField(guard, "toolTimeoutMs", 100L);

        AssistantToolGuard.Outcome outcome = guard.invoke("u-1", "p", tool("cost_trend", a -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        }), Map.of());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.text()).contains("took too long");
        verify(auditService).logFailure(eq("u-1"), eq(AuditAction.ASSISTANT_TOOL_CALLED), anyString(), eq("cost_trend"),
                anyString(), org.mockito.ArgumentMatchers.startsWith("timed out"));
    }

    @Test
    void refusalsShowTheirMessageAndFailuresDoNot() {
        AssistantToolGuard.Outcome notFound = guard.invoke("u-1", "p",
                tool("get_environment", a -> { throw new ResourceNotFoundException("Environment not found: x"); }), Map.of());
        AssistantToolGuard.Outcome broken = guard.invoke("u-1", "p",
                tool("get_environment", a -> { throw new IllegalStateException("db password is hunter2"); }), Map.of());

        assertThat(notFound.error()).isTrue();
        assertThat(notFound.text()).isEqualTo("Environment not found: x");
        assertThat(broken.error()).isTrue();
        assertThat(broken.text()).isEqualTo("The tool failed");
    }

    @Test
    void longArgumentsAreShortenedInTheAuditAndAFailingAuditDoesNotFailTheCall() {
        doThrow(new RuntimeException("audit down")).when(auditService).logAction(any(), any(), any(), any(), any(), any());

        AssistantToolGuard.Outcome outcome = guard.invoke("u-1", "p", tool("list_environments", a -> Map.of()),
                Map.of("search", "y".repeat(1000)));

        assertThat(outcome.error()).isFalse();
        assertThat(AssistantToolGuard.summarise(Map.of("search", "y".repeat(1000)))).hasSize("search=".length() + 200 + 1);
        verify(auditService, never()).logFailure(any(), any(), any(), any(), any(), any());
    }
}
