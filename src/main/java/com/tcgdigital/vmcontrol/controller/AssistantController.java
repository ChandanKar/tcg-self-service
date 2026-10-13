package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.assistant.chat.AssistantChatService;
import com.tcgdigital.vmcontrol.service.assistant.chat.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The in-app Ask panel (E36-T05): signed-in users ask questions about their environments and costs;
 * the answer comes from the configured model using the read-only assistant tools as that user.
 * 404 while assistant.chat.enabled is off (or no API key is set); 429 past the daily question limit.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@PreAuthorize("isAuthenticated()")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    public record AskRequest(String question, List<AssistantChatService.HistoryTurn> history) {
    }

    private final AssistantChatService chatService;
    private final UserService userService;

    public AssistantController(AssistantChatService chatService, UserService userService) {
        this.chatService = chatService;
        this.userService = userService;
    }

    /** Whether the panel is on, for the user menu; the limit and what is left today when it is. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        if (!chatService.isEnabled()) return Map.of("enabled", false);
        User user = userService.getCurrentUser();
        return Map.of("enabled", true, "dailyLimit", chatService.dailyLimit(),
                "remainingToday", user == null ? 0 : chatService.remainingToday(user.getUserId()));
    }

    @PostMapping("/ask")
    public ResponseEntity<Object> ask(@RequestBody AskRequest request) {
        if (!chatService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found", "message", "The assistant is not enabled"));
        }
        User user = userService.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized", "message", "Sign in again"));
        }
        try {
            return ResponseEntity.ok(chatService.ask(user, request == null ? null : request.question(),
                    request == null ? null : request.history()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bad Request", "message", e.getMessage()));
        } catch (AssistantChatService.QuotaExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too Many Requests", "message", e.getMessage()));
        } catch (LlmException e) {
            log.warn("Assistant model call failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Bad Gateway", "message", "The assistant is unavailable right now. Try again later."));
        }
    }
}
