package com.tcgdigital.vmcontrol.service.assistant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Posts JSON to a model provider and reads the JSON answer (E36-T05). The API key never appears in errors. */
@Component
public class LlmHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public LlmHttp(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public JsonNode post(String url, Map<String, String> headers, Object body) {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } catch (IOException | IllegalArgumentException e) {
            throw new LlmException("Bad model request: " + e.getMessage(), e);
        }
        headers.forEach(request::header);
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("Model provider unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            String text = response.body() == null ? "" : response.body();
            throw new LlmException("Model provider answered " + response.statusCode() + ": "
                    + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new LlmException("Model provider answered something other than JSON", e);
        }
    }
}
