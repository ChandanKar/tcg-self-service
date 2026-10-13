package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.security.ApiTokenAuthentication;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.service.assistant.AssistantTool;
import com.tcgdigital.vmcontrol.service.assistant.AssistantToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A read-only MCP server (E36-T01) at /mcp, for AI assistants such as Claude Desktop or Claude Code.
 *
 * <p>It speaks the Streamable HTTP transport's request/response part: JSON-RPC 2.0 messages are
 * POSTed to /mcp and every answer is a single {@code application/json} body (the transport allows
 * that instead of an event stream); notifications get 202; there is no server-initiated stream
 * (GET answers 405) and no session to end (DELETE answers 405). Supported methods: initialize,
 * ping, tools/list and tools/call. Implemented here rather than with an MCP library so that it
 * runs behind the same bearer-token filter and adds no dependency.
 *
 * <p>Only API tokens with the assistant:read scope get in (the token filter checks the scope); a
 * browser session gets 401, so a page cannot drive it cross-site. Off (assistant.mcp.enabled, or
 * API tokens off) it answers 404. Every tool call goes through {@link AssistantToolGuard} (E36-T03):
 * rate limit, timeout, output cap and audit; a rate-limited call is JSON-RPC error -32000.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    @Value("${assistant.mcp.enabled:false}")
    private boolean enabled;

    private final ApiTokenService tokenService;
    private final Map<String, AssistantTool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final AssistantToolGuard guard;

    public McpController(ApiTokenService tokenService, List<AssistantTool> tools, ObjectMapper objectMapper,
                         AssistantToolGuard guard) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.guard = guard;
        tools.stream().sorted((a, b) -> a.name().compareTo(b.name())).forEach(t -> this.tools.put(t.name(), t));
    }

    public boolean isEnabled() {
        return enabled && tokenService.isEnabled();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> post(@RequestBody JsonNode message) {
        ResponseEntity<Object> refused = refuse();
        if (refused != null) return refused;
        if (!message.isObject()) {
            return ResponseEntity.ok(error(null, -32600, "Send one JSON-RPC message per request"));
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText("");
        if (id == null || id.isNull()) {
            // A notification (initialized, cancelled, ...) or a response from the client: nothing to answer.
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        try {
            return ResponseEntity.ok(switch (method) {
                case "initialize" -> result(id, initialize(message.path("params")));
                case "ping" -> result(id, Map.of());
                case "tools/list" -> result(id, Map.of("tools", toolList()));
                case "tools/call" -> callTool(id, message.path("params"));
                default -> error(id, -32601, "Method not found: " + method);
            });
        } catch (Exception e) {
            log.warn("MCP {} failed: {}", method, e.getMessage());
            return ResponseEntity.ok(error(id, -32603, "Internal error"));
        }
    }

    @GetMapping
    public ResponseEntity<Object> stream() {
        ResponseEntity<Object> refused = refuse();
        return refused != null ? refused : ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping
    public ResponseEntity<Object> endSession() {
        ResponseEntity<Object> refused = refuse();
        return refused != null ? refused : ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /** 404 while off, 401 for anything but an API token (the filter has already checked its scope). */
    private ResponseEntity<Object> refuse() {
        if (!isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found", "message", "The MCP server is not enabled"));
        }
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof ApiTokenAuthentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Use an API token with the assistant:read scope"));
        }
        return null;
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "vmcontrol", "version", "1.0"));
        result.put("instructions", "Read-only access to the VM self-service platform as the token's owner: environments, "
                + "costs, savings and idle resources you may see. Nothing can be started, stopped or changed here.");
        return result;
    }

    private List<Map<String, Object>> toolList() {
        List<Map<String, Object>> list = new ArrayList<>();
        tools.values().forEach(t -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", t.name());
            entry.put("description", t.description());
            entry.put("inputSchema", t.inputSchema());
            entry.put("annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));
            list.add(entry);
        });
        return list;
    }

    private Map<String, Object> callTool(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        AssistantTool tool = tools.get(name);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.has("arguments") && params.get("arguments").isObject()
                ? objectMapper.convertValue(params.get("arguments"), Map.class) : Map.of();
        ApiTokenAuthentication auth = (ApiTokenAuthentication) SecurityContextHolder.getContext().getAuthentication();
        AssistantToolGuard.Outcome outcome = guard.invoke((String) auth.getPrincipal(), auth.getTokenPrefix(), tool, arguments);
        if (outcome.rateLimited()) {
            Map<String, Object> response = error(id, -32000, outcome.text());
            ((ObjectNode) response.get("error")).putObject("data").put("retryAfterSeconds", outcome.retryAfterSeconds());
            return response;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", outcome.text()));
        if (outcome.truncated()) {
            content.add(Map.of("type", "text", "text", "[Output truncated: the result was too long. Ask for fewer rows or one environment.]"));
            result.put("_meta", Map.of("truncated", true));
        }
        result.put("content", content);
        if (outcome.structured() != null) result.put("structuredContent", outcome.structured());
        result.put("isError", outcome.error());
        return result(id, result);
    }

    private Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }
}
