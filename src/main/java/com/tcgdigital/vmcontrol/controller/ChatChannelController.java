package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.ChatChannelDTO;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.chat.ChatChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Chat (Microsoft Teams) channels (E24-T02): an environment's channels are managed by those who
 * administer it; global default channels (no environment) by admins only.
 */
@RestController
@RequestMapping("/api/v1/chat-channels")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Chat channels", description = "Microsoft Teams channels that receive environment notifications")
public class ChatChannelController {

    private final ChatChannelService channelService;
    private final SecurityService securityService;
    private final UserService userService;

    public ChatChannelController(ChatChannelService channelService, SecurityService securityService, UserService userService) {
        this.channelService = channelService;
        this.securityService = securityService;
        this.userService = userService;
    }

    /** ADMIN for a global channel (null), else whoever administers the environment. */
    private void assertCanManage(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            if (!securityService.isAdmin()) {
                throw new UnauthorizedException("Only admins manage default chat channels");
            }
            return;
        }
        securityService.assertCanAdminister(environmentId);
    }

    @GetMapping("/options")
    @Operation(summary = "Providers and event types for the channel editor, and whether chat is on")
    public ChatChannelDTO.Options options() {
        return channelService.options();
    }

    @GetMapping
    @Operation(summary = "An environment's channels; without environmentId all channels (admins only), or only the defaults with global=true")
    public List<ChatChannelDTO> list(@RequestParam(required = false) String environmentId,
                                     @RequestParam(defaultValue = "false") boolean global) {
        assertCanManage(environmentId);
        return channelService.list(environmentId == null || environmentId.isBlank() ? null : environmentId, global);
    }

    @GetMapping("/{channelId}")
    public ChatChannelDTO get(@PathVariable String channelId) {
        assertCanManage(channelService.environmentOf(channelId));
        return channelService.get(channelId);
    }

    @PostMapping
    @Operation(summary = "Add a channel; the webhook URL is stored encrypted and never returned")
    public ResponseEntity<ChatChannelDTO> create(@RequestBody ChatChannelDTO.Request request) {
        assertCanManage(request == null ? null : request.environmentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(channelService.create(userService.getCurrentUserId(), request));
    }

    @PutMapping("/{channelId}")
    @Operation(summary = "Change a channel; omit webhookUrl to keep the stored one")
    public ChatChannelDTO update(@PathVariable String channelId, @RequestBody ChatChannelDTO.Request request) {
        assertCanManage(channelService.environmentOf(channelId));
        return channelService.update(channelId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{channelId}")
    public ResponseEntity<Void> delete(@PathVariable String channelId) {
        assertCanManage(channelService.environmentOf(channelId));
        channelService.delete(channelId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{channelId}/test")
    @Operation(summary = "Send a test message; returns delivered and, on failure, the kind of error (never the URL)")
    public ChatChannelDTO.TestResult test(@PathVariable String channelId) {
        assertCanManage(channelService.environmentOf(channelId));
        return channelService.sendTest(channelId);
    }
}
