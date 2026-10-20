package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.chat.ChatActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * One-click chat action links (E24-T04). The token only says what to do; it runs as the signed-in
 * user with their normal permissions. POST needs the CSRF token like any other change.
 */
@RestController
@RequestMapping("/api/v1/chat-actions")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Chat actions", description = "Confirm and run one-click actions from Teams messages")
public class ChatActionController {

    private final ChatActionService actionService;
    private final UserService userService;

    public ChatActionController(ChatActionService actionService, UserService userService) {
        this.actionService = actionService;
        this.userService = userService;
    }

    @GetMapping("/{token}")
    @Operation(summary = "What the link would do (400 invalid/expired, 409 used, 403 not allowed)")
    public ChatActionService.Preview preview(@PathVariable String token) {
        return actionService.preview(token);
    }

    /** Optional: minutes (extend lease) or hours (snooze idle stop). */
    public record ExecuteRequest(Integer choice) {}

    @PostMapping("/{token}")
    @Operation(summary = "Run the action as the signed-in user; the link then stops working")
    public ChatActionService.Result execute(@PathVariable String token, @RequestBody(required = false) ExecuteRequest body) {
        return actionService.execute(token, userService.getCurrentUserId(), body == null ? null : body.choice());
    }

    /**
     * A link that cannot be used answers with the usual status and body plus {@code code}, the
     * environment (when the caller can see it) and an admin to ask (U09-T08), so the confirm page
     * can say what happened instead of a generic error.
     */
    @ExceptionHandler({com.tcgdigital.vmcontrol.exception.ValidationException.class,
            com.tcgdigital.vmcontrol.exception.ConflictException.class,
            com.tcgdigital.vmcontrol.exception.UnauthorizedException.class,
            com.tcgdigital.vmcontrol.exception.ResourceNotFoundException.class,
            org.springframework.security.access.AccessDeniedException.class})
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> linkFailure(
            RuntimeException ex, jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        String token = uri.substring(uri.lastIndexOf('/') + 1);
        String userId;
        try {
            userId = userService.getCurrentUserId();
        } catch (RuntimeException e) {
            userId = null;
        }
        ChatActionService.Failure f = actionService.failure(token, ex, userId);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", switch (f.status()) {
            case 400 -> "Validation Error";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            default -> "Conflict";
        });
        body.put("message", f.message());
        body.put("code", f.code());
        if (f.environmentId() != null) body.put("environmentId", f.environmentId());
        if (f.environmentName() != null) body.put("environmentName", f.environmentName());
        if (f.adminName() != null) body.put("adminName", f.adminName());
        body.put("timestamp", java.time.Instant.now().toString());
        return org.springframework.http.ResponseEntity.status(f.status()).body(body);
    }
}
