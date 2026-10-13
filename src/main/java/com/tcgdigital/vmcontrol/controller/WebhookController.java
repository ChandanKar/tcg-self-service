package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.controller.support.Paging;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.webhook.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Webhook subscriptions (E34-T06): admins for global ones, environment admins for their environments.
 * 404 while webhooks.enabled is off.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Signed outbound webhooks")
@PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
public class WebhookController {

    private final WebhookService webhookService;
    private final UserService userService;

    public WebhookController(WebhookService webhookService, UserService userService) {
        this.webhookService = webhookService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<WebhookService.WebhookView>> list(@RequestParam(required = false) String environmentId) {
        return ResponseEntity.ok(webhookService.list(environmentId));
    }

    @PostMapping
    @Operation(summary = "Create a webhook", description = "The answer carries the signing secret; it is never shown again.")
    public ResponseEntity<WebhookService.Created> create(@RequestBody WebhookService.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhookService.create(request, userService.getCurrentUserId()));
    }

    @PutMapping("/{webhookId}")
    public ResponseEntity<WebhookService.WebhookView> update(@PathVariable String webhookId,
                                                             @RequestBody WebhookService.UpdateRequest request) {
        return ResponseEntity.ok(webhookService.update(webhookId, request, userService.getCurrentUserId()));
    }

    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String webhookId) {
        webhookService.delete(webhookId, userService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/{webhookId}/test")
    @Operation(summary = "Send a ping", description = "Sent now; the answer reports the receiver's status.")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String webhookId) {
        return ResponseEntity.ok(webhookService.test(webhookId));
    }

    @GetMapping("/{webhookId}/deliveries")
    public ResponseEntity<Page<WebhookService.DeliveryView>> deliveries(@PathVariable String webhookId,
                                                                        @RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(webhookService.deliveries(webhookId, Paging.of(page, size, 100)));
    }
}
