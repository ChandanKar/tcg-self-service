package com.tcgdigital.vmcontrol.service.assistant.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An OpenAI-compatible chat completions endpoint (E36-T05): POST {endpoint}/chat/completions, where
 * the endpoint includes the version path (for example https://api.openai.com/v1 or a gateway's).
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmHttp http;

    public OpenAiCompatibleLlmClient(LlmHttp http) {
        this.http = http;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public Reply complete(Request request) {
        String endpoint = request.endpoint().replaceAll("/+$", "");
        JsonNode answer = http.post(endpoint + "/chat/completions",
                Map.of("Authorization", "Bearer " + request.apiKey()), body(request, http.mapper()));
        return parse(answer, http.mapper());
    }

    static Map<String, Object> body(Request request, ObjectMapper mapper) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.system()));
        for (Turn turn : request.turns()) {
            if (turn instanceof UserText u) {
                messages.add(Map.of("role", "user", "content", u.text()));
            } else if (turn instanceof AssistantReply a) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", a.text().isBlank() ? null : a.text());
                if (!a.toolCalls().isEmpty()) {
                    m.put("tool_calls", a.toolCalls().stream().map(c -> Map.of("id", c.id(), "type", "function",
                            "function", Map.of("name", c.name(), "arguments", json(mapper, c.arguments())))).toList());
                }
                messages.add(m);
            } else if (turn instanceof ToolResults r) {
                r.results().forEach(x -> messages.add(Map.of("role", "tool", "tool_call_id", x.callId(),
                        "content", x.error() ? "Error: " + x.text() : x.text())));
            }
        }
        body.put("messages", messages);
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(t -> Map.of("type", "function", "function",
                    Map.of("name", t.name(), "description", t.description(), "parameters", t.inputSchema()))).toList());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    static Reply parse(JsonNode answer, ObjectMapper mapper) {
        JsonNode message = answer.path("choices").path(0).path("message");
        if (message.isMissingNode()) throw new LlmException("Chat completions answer without a message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            Map<String, Object> args;
            try {
                String raw = call.path("function").path("arguments").asText("");
                args = raw.isBlank() ? Map.of() : mapper.readValue(raw, Map.class);
            } catch (JsonProcessingException e) {
                args = Map.of();
            }
            calls.add(new ToolCall(call.path("id").asText(), call.path("function").path("name").asText(), args));
        }
        return new Reply(message.path("content").isTextual() ? message.path("content").asText() : "", calls);
    }

    private static String json(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
