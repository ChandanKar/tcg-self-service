package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.ApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreatedApiTokenDTO;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.security.ApiTokenAuthentication;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The signed-in user's own API tokens (E34-T01); 404 while api-tokens.enabled is off. Only a browser
 * session may create or revoke tokens: a token cannot make another.
 */
@RestController
@RequestMapping("/api/v1/me/api-tokens")
@Tag(name = "API tokens", description = "Personal API tokens")
@PreAuthorize("isAuthenticated()")
public class ApiTokenController {

    private final ApiTokenService tokenService;
    private final UserService userService;

    public ApiTokenController(ApiTokenService tokenService, UserService userService) {
        this.tokenService = tokenService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<ApiTokenDTO>> list() {
        return ResponseEntity.ok(tokenService.list(sessionUser().getUserId()));
    }

    @PostMapping
    @Operation(summary = "Create a token", description = "The answer carries the token; it is never shown again.")
    public ResponseEntity<CreatedApiTokenDTO> create(@RequestBody CreateApiTokenDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tokenService.create(sessionUser(), dto));
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Map<String, String>> revoke(@PathVariable String tokenId) {
        User user = sessionUser();
        tokenService.revoke(user.getUserId(), tokenId, user.getUserId());
        return ResponseEntity.ok(Map.of("status", "revoked"));
    }

    private User sessionUser() {
        tokenService.requireEnabled();
        if (SecurityContextHolder.getContext().getAuthentication() instanceof ApiTokenAuthentication) {
            throw new UnauthorizedException("API tokens cannot manage API tokens; sign in to the web app");
        }
        User user = userService.getCurrentUser();
        if (user == null) {
            throw new UnauthorizedException("Not signed in");
        }
        return user;
    }
}
