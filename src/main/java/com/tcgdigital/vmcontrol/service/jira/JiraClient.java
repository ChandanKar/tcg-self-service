package com.tcgdigital.vmcontrol.service.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.exception.IntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Jira REST v3 (E24-T06): creates an issue with an ADF description. Jira Cloud uses Basic auth
 * with jira.email + jira.api-token; without an email the token is sent as a Bearer personal access
 * token (Data Center). The token is never logged. 401/403 = "Jira credentials rejected" (502).
 */
@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${jira.base-url:}")
    private String baseUrl = "";

    @Value("${jira.email:}")
    private String email = "";

    @Value("${jira.api-token:}")
    private String apiToken = "";

    @org.springframework.beans.factory.annotation.Autowired
    public JiraClient(RestClient.Builder builder, ObjectMapper objectMapper,
                      @Value("${jira.timeout-ms:10000}") long timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = builder.clone().requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    /** For tests: a client bound to a mock server. */
    JiraClient(RestClient restClient, ObjectMapper objectMapper, String baseUrl, String email, String apiToken) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.email = email;
        this.apiToken = apiToken;
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    public String baseUrl() {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /** The new issue: its key and browse URL. */
    public record Issue(String key, String url) {}

    /** A paragraph of the description; link set = the text links there. */
    public record Paragraph(String text, String link) {
        public static Paragraph text(String text) {
            return new Paragraph(text, null);
        }
    }

    public Issue createIssue(String projectKey, String issueType, String summary, List<Paragraph> description,
                             List<String> labels) {
        if (!isConfigured()) {
            throw new IntegrationException(HttpStatus.CONFLICT, "jira_not_configured",
                    "Jira is not configured: set JIRA_BASE_URL and JIRA_API_TOKEN");
        }
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode fields = body.putObject("fields");
        fields.putObject("project").put("key", projectKey);
        fields.putObject("issuetype").put("name", issueType);
        fields.put("summary", summary.length() > 250 ? summary.substring(0, 249) + "…" : summary);
        fields.set("description", adf(description));
        ArrayNode labelArray = fields.putArray("labels");
        labels.forEach(labelArray::add);

        record Response(int status, String body) {}
        Response response;
        try {
            response = restClient.post()
                    .uri(baseUrl() + "/rest/api/3/issue")
                    .header(HttpHeaders.AUTHORIZATION, authorization())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .exchange((request, res) -> {
                        String text;
                        try {
                            text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            text = "";
                        }
                        return new Response(res.getStatusCode().value(), text);
                    });
        } catch (RestClientException e) {
            log.warn("Jira unreachable: {}", e.getMostSpecificCause().getClass().getSimpleName());
            throw new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_unavailable", "Jira is unreachable");
        }
        if (response.status() == 401 || response.status() == 403) {
            log.warn("Jira rejected the credentials (HTTP {})", response.status());
            throw new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_auth", "Jira credentials rejected");
        }
        if (response.status() == 400) {
            throw new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_rejected", "Jira rejected the issue: " + errors(response.body()));
        }
        if (response.status() < 200 || response.status() >= 300) {
            log.warn("Jira answered HTTP {}", response.status());
            throw new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_unavailable", "Jira returned HTTP " + response.status());
        }
        try {
            String key = objectMapper.readTree(response.body()).path("key").asText(null);
            if (key == null || key.isBlank()) {
                throw new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_unavailable", "Jira did not return an issue key");
            }
            return new Issue(key, baseUrl() + "/browse/" + key);
        } catch (IOException e) {
            throw new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_unavailable", "Jira returned an unreadable answer");
        }
    }

    private String authorization() {
        if (email != null && !email.isBlank()) {
            return "Basic " + Base64.getEncoder().encodeToString((email.trim() + ":" + apiToken.trim()).getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + apiToken.trim();
    }

    /** Atlassian Document Format: one paragraph per entry, linked text where a link is given. */
    ObjectNode adf(List<Paragraph> paragraphs) {
        ObjectNode doc = objectMapper.createObjectNode().put("type", "doc").put("version", 1);
        ArrayNode content = doc.putArray("content");
        for (Paragraph p : paragraphs) {
            ObjectNode text = content.addObject().put("type", "paragraph").putArray("content").addObject()
                    .put("type", "text").put("text", p.text() == null || p.text().isEmpty() ? " " : p.text());
            if (p.link() != null) {
                text.putArray("marks").addObject().put("type", "link").putObject("attrs").put("href", p.link());
            }
        }
        return doc;
    }

    /** Jira's field errors ("summary: is required") and messages, joined. */
    private String errors(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            StringBuilder out = new StringBuilder();
            root.path("errors").fields().forEachRemaining(e -> out.append(out.isEmpty() ? "" : "; ")
                    .append(e.getKey()).append(": ").append(e.getValue().asText()));
            root.path("errorMessages").forEach(m -> out.append(out.isEmpty() ? "" : "; ").append(m.asText()));
            return out.isEmpty() ? "HTTP 400" : out.toString();
        } catch (IOException e) {
            return "HTTP 400";
        }
    }
}
