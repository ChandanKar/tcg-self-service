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
    private final com.tcgdigital.vmcontrol.service.GraphDirectoryService graph;
    private final com.tcgdigital.vmcontrol.service.TeamMembershipSyncService syncService;

    public TeamController(TeamService teamService, UserService userService,
                          com.tcgdigital.vmcontrol.service.GraphDirectoryService graph,
                          com.tcgdigital.vmcontrol.service.TeamMembershipSyncService syncService) {
        this.teamService = teamService;
        this.userService = userService;
        this.graph = graph;
        this.syncService = syncService;
    }

    // ============= Entra groups and members (E23-T02) =============

    public record GroupLink(String groupId) {}

    public record MemberRequest(@jakarta.validation.constraints.NotBlank(message = "Choose a user") String userId) {}

    @GetMapping("/entra-groups")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Search Entra groups to link a team (409 when directory lookup is off)")
    public List<com.tcgdigital.vmcontrol.service.GraphDirectoryService.DirectoryGroup> entraGroups(
            @RequestParam(defaultValue = "") String query, @RequestParam(required = false) Integer top) {
        return graph.searchGroups(query, top);
    }

    @PutMapping("/{teamId}/entra-group")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Link (or with null, unlink) the team's Entra group; syncs its members at once when lookup is on")
    public TeamDTO linkEntraGroup(@PathVariable String teamId, @RequestBody GroupLink body) {
        TeamDTO team = teamService.linkEntraGroup(teamId, body == null ? null : body.groupId(), userService.getCurrentUserId());
        if (team.entraGroupId() != null && syncService.isAvailable()) {
            syncService.syncTeam(teamId);
            return teamService.get(teamId);
        }
        return team;
    }

    @PostMapping("/{teamId}/sync")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Sync the team's members from its Entra group now")
    public com.tcgdigital.vmcontrol.service.TeamMembershipSyncService.Result sync(@PathVariable String teamId) {
        if (!syncService.isAvailable()) {
            throw com.tcgdigital.vmcontrol.exception.DirectoryLookupException.disabled();
        }
        return syncService.syncTeam(teamId);
    }

    @GetMapping("/{teamId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The team's members and where each came from (ENTRA or MANUAL)")
    public List<TeamService.MemberView> members(@PathVariable String teamId) {
        return teamService.members(teamId);
    }

    @PostMapping("/{teamId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a member by hand")
    public TeamService.MemberView addMember(@PathVariable String teamId, @Valid @RequestBody MemberRequest body) {
        return teamService.addMember(teamId, body.userId(), userService.getCurrentUserId());
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a member (an Entra member comes back on the next sync while still in the group)")
    public ResponseEntity<Void> removeMember(@PathVariable String teamId, @PathVariable String userId) {
        teamService.removeMember(teamId, userId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
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
