package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.ApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreatedApiTokenDTO;
import com.tcgdigital.vmcontrol.service.ServiceAccountService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Service accounts and their tokens (E34-T03): admins only; 404 while API tokens are off. Not open to tokens. */
@RestController
@RequestMapping("/api/v1/service-accounts")
@Tag(name = "Service accounts", description = "Accounts for CI and automation")
@PreAuthorize("hasRole('ADMIN')")
public class ServiceAccountController {

    private final ServiceAccountService service;
    private final UserService userService;

    public ServiceAccountController(ServiceAccountService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<ServiceAccountService.ServiceAccountView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "Create a service account", description = "Role USER or ENV_ADMIN; grant it environments like any user.")
    public ResponseEntity<ServiceAccountService.ServiceAccountView> create(@RequestBody ServiceAccountService.CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, userService.getCurrentUserId()));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Deactivate a service account", description = "Its tokens are revoked.")
    public ResponseEntity<ServiceAccountService.ServiceAccountView> deactivate(@PathVariable String userId) {
        return ResponseEntity.ok(service.deactivate(userId, userService.getCurrentUserId()));
    }

    @GetMapping("/{userId}/tokens")
    public ResponseEntity<List<ApiTokenDTO>> tokens(@PathVariable String userId) {
        return ResponseEntity.ok(service.tokens(userId));
    }

    @PostMapping("/{userId}/tokens")
    @Operation(summary = "Issue a token", description = "The answer carries the token; it is never shown again.")
    public ResponseEntity<CreatedApiTokenDTO> issue(@PathVariable String userId, @RequestBody CreateApiTokenDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueToken(userId, dto, userService.getCurrentUserId()));
    }

    @DeleteMapping("/{userId}/tokens/{tokenId}")
    public ResponseEntity<Map<String, String>> revoke(@PathVariable String userId, @PathVariable String tokenId) {
        service.revokeToken(userId, tokenId, userService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("status", "revoked"));
    }
}
