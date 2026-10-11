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

    @PostMapping("/{token}")
    @Operation(summary = "Run the action as the signed-in user; the link then stops working")
    public ChatActionService.Result execute(@PathVariable String token) {
        return actionService.execute(token, userService.getCurrentUserId());
    }
}
