package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.TeamDTO;
import com.tcgdigital.vmcontrol.service.TeamService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Teams (E23-T01): anyone signed in lists the active ones; admins manage them. */
@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "Teams", description = "Teams that own environments and are charged for them")
public class TeamController {

    private final TeamService teamService;
    private final UserService userService;

    public TeamController(TeamService teamService, UserService userService) {
        this.teamService = teamService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Active teams (admins may include inactive ones)")
    public List<TeamDTO> list(@RequestParam(defaultValue = "false") boolean includeInactive,
                              org.springframework.security.core.Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return teamService.list(includeInactive && admin);
    }

    @GetMapping("/{teamId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a team")
    public TeamDTO get(@PathVariable String teamId) {
        return teamService.get(teamId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a team")
    public ResponseEntity<TeamDTO> create(@Valid @RequestBody TeamDTO.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(teamService.create(userService.getCurrentUserId(), request));
    }

    @PutMapping("/{teamId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename a team or change its cost center")
    public TeamDTO update(@PathVariable String teamId, @Valid @RequestBody TeamDTO.Request request) {
        return teamService.update(teamId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{teamId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a team (409 while it owns active environments)")
    public TeamDTO deactivate(@PathVariable String teamId) {
        return teamService.deactivate(teamId, userService.getCurrentUserId());
    }
}
