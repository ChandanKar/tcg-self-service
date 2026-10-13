package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.controller.support.Paging;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Every API token, for admins (E34-T05): who holds what, and revoking any of them. 404 while tokens are off. */
@RestController
@RequestMapping("/api/v1/api-tokens")
@Tag(name = "API tokens (admin)", description = "All API tokens")
@PreAuthorize("hasRole('ADMIN')")
public class AdminApiTokenController {

    private final ApiTokenService tokenService;
    private final UserService userService;

    public AdminApiTokenController(ApiTokenService tokenService, UserService userService) {
        this.tokenService = tokenService;
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List tokens", description = "Active tokens, newest first; includeInactive adds revoked and expired ones.")
    public ResponseEntity<Page<ApiTokenService.AdminTokenView>> list(@RequestParam(defaultValue = "false") boolean includeInactive,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(tokenService.listAll(includeInactive,
                Paging.of(page, size, 100, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Map<String, String>> revoke(@PathVariable String tokenId) {
        tokenService.adminRevoke(tokenId, userService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("status", "revoked"));
    }
}
