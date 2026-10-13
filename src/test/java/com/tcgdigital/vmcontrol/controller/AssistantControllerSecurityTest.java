package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.service.assistant.chat.AssistantChatService;
import com.tcgdigital.vmcontrol.service.assistant.chat.LlmClient;
import com.tcgdigital.vmcontrol.service.assistant.chat.LlmException;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Ask panel's API (E36-T05): off is 404, signed-in users only, CSRF, daily quota, provider failures. */
class AssistantControllerSecurityTest extends SecuredWebTestBase {

    @Autowired private AssistantChatService chatService;

    private Object target;
    private LlmClient original;
    private Function<Integer, LlmClient.Reply> script;

    private final LlmClient stub = new LlmClient() {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String provider() {
            return "anthropic";
        }

        @Override
        public Reply complete(Request request) {
            return script.apply(calls.incrementAndGet());
        }
    };

    @SuppressWarnings("unchecked")
    private Map<String, LlmClient> clients() {
        return (Map<String, LlmClient>) ReflectionTestUtils.getField(target, "clients");
    }

    private void on(boolean enabled) {
        ReflectionTestUtils.setField(target, "enabled", enabled);
        ReflectionTestUtils.setField(target, "apiKey", enabled ? "test-key" : "");
    }

    @BeforeEach
    void setUp() {
        target = AopTestUtils.getTargetObject(chatService);
        original = clients().get("anthropic");
        clients().put("anthropic", stub);
        script = n -> n == 1
                ? new LlmClient.Reply("", List.of(new LlmClient.ToolCall("c1", "list_environments", Map.of())))
                : new LlmClient.Reply("<b>You</b> have no environments.", List.of());
        on(true);
    }

    @AfterEach
    void tearDown() {
        clients().put("anthropic", original);
        ReflectionTestUtils.setField(target, "dailyQuestionsPerUser", 50);
        on(false);
    }

    private MockHttpServletRequestBuilder ask(RequestPostProcessor who, String question) {
        return post("/api/v1/assistant/ask").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + question + "\",\"history\":[{\"role\":\"user\",\"text\":\"hi\"},"
                        + "{\"role\":\"assistant\",\"text\":\"hello\"}]}");
    }

    @Test
    void aSignedInUserGetsTheAnswerAndTheToolsUsed() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/status").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.dailyLimit").value(50));

        mockMvc.perform(ask(asViewer(), "Which of my environments were idle this week?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("<b>You</b> have no environments."))
                .andExpect(jsonPath("$.toolCalls[0].name").value("list_environments"))
                .andExpect(jsonPath("$.toolCalls[0].error").value(false))
                .andExpect(jsonPath("$.incomplete").value(false));
    }

    @Test
    void offIsNotFoundAndTheStatusSaysSo() throws Exception {
        on(false);

        mockMvc.perform(get("/api/v1/assistant/status").with(asUser())).andExpect(jsonPath("$.enabled").value(false));
        expectError(mockMvc.perform(ask(asUser(), "hello")), 404);
    }

    @Test
    void anonymousAndCsrfLessRequestsAreRefused() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}"))
                .andExpect(status().is4xxClientError());
        expectError(mockMvc.perform(ask(asWithoutCsrf(operator), "q")), 403);
    }

    @Test
    void pastTheDailyLimitIs429AndAProviderFailureIs502() throws Exception {
        ReflectionTestUtils.setField(target, "dailyQuestionsPerUser", 1);
        script = n -> new LlmClient.Reply("ok", List.of());
        mockMvc.perform(ask(asUser(), "one")).andExpect(status().isOk());
        expectError(mockMvc.perform(ask(asUser(), "two")), 429);

        ReflectionTestUtils.setField(target, "dailyQuestionsPerUser", 50);
        script = n -> { throw new LlmException("Model provider answered 500: secret detail"); };
        mockMvc.perform(ask(asEnvAdmin(), "three"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("The assistant is unavailable right now. Try again later."));
        expectError(mockMvc.perform(ask(asEnvAdmin(), " ")), 400);
    }
}
