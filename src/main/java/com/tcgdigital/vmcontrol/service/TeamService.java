package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.TeamDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Teams (E23-T01): unique slug names, and a team can only be deactivated when no active
 * environment belongs to it. Changes are audited.
 */
@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final AuditService auditService;

    public TeamService(TeamRepository teamRepository, AuditService auditService) {
        this.teamRepository = teamRepository;
        this.auditService = auditService;
    }

    /** "Platform Team " -> "platform-team". */
    public static String slug(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    @Transactional(readOnly = true)
    public List<TeamDTO> list(boolean includeInactive) {
        Map<String, Long> environments = environmentCounts();
        List<Team> teams = includeInactive ? teamRepository.findAllByOrderByDisplayName()
                : teamRepository.findByIsActiveTrueOrderByDisplayName();
        return teams.stream().map(t -> toDto(t, environments)).toList();
    }

    @Transactional(readOnly = true)
    public TeamDTO get(String teamId) {
        return toDto(team(teamId), environmentCounts());
    }

    @Transactional
    public TeamDTO create(String userId, TeamDTO.Request request) {
        String name = slug(request.name() == null || request.name().isBlank() ? request.displayName() : request.name());
        if (name.isEmpty()) {
            throw new ValidationException("The team name needs at least one letter or digit");
        }
        if (teamRepository.findByName(name).isPresent()) {
            throw new ValidationException("A team named '" + name + "' already exists");
        }
        Team team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName(name);
        team.setDisplayName(request.displayName().trim());
        team.setCostCenter(blankToNull(request.costCenter()));
        team = teamRepository.save(team);
        auditService.logAction(userId, AuditAction.TEAM_CREATED, "team", team.getTeamId(), team.getDisplayName(),
                "Created team " + name + (team.getCostCenter() == null ? "" : " (cost center " + team.getCostCenter() + ")"));
        return toDto(team, environmentCounts());
    }

    @Transactional
    public TeamDTO update(String teamId, String userId, TeamDTO.Request request) {
        Team team = team(teamId);
        if (request.name() != null && !request.name().isBlank()) {
            String name = slug(request.name());
            if (name.isEmpty()) {
                throw new ValidationException("The team name needs at least one letter or digit");
            }
            teamRepository.findByName(name).filter(t -> !t.getTeamId().equals(teamId)).ifPresent(t -> {
                throw new ValidationException("A team named '" + name + "' already exists");
            });
            team.setName(name);
        }
        team.setDisplayName(request.displayName().trim());
        team.setCostCenter(blankToNull(request.costCenter()));
        teamRepository.save(team);
        auditService.logAction(userId, AuditAction.TEAM_UPDATED, "team", teamId, team.getDisplayName(),
                "Updated team " + team.getName());
        return toDto(team, environmentCounts());
    }

    /** Refused while an active environment belongs to the team. */
    @Transactional
    public TeamDTO deactivate(String teamId, String userId) {
        Team team = team(teamId);
        long environments = environmentCounts().getOrDefault(teamId, 0L);
        if (environments > 0) {
            throw new ConflictException("Team " + team.getDisplayName() + " still owns " + environments
                    + " active environment" + (environments == 1 ? "" : "s") + "; move them to another team first");
        }
        team.setIsActive(false);
        teamRepository.save(team);
        auditService.logAction(userId, AuditAction.TEAM_DEACTIVATED, "team", teamId, team.getDisplayName(),
                "Deactivated team " + team.getName());
        return toDto(team, environmentCounts());
    }

    private Team team(String teamId) {
        return teamRepository.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
    }

    private Map<String, Long> environmentCounts() {
        Map<String, Long> counts = new HashMap<>();
        teamRepository.countActiveEnvironments().forEach(r -> counts.put((String) r[0], ((Number) r[1]).longValue()));
        return counts;
    }

    TeamDTO toDto(Team t, Map<String, Long> environments) {
        return new TeamDTO(t.getTeamId(), t.getName(), t.getDisplayName(), t.getCostCenter(), t.getEntraGroupId(),
                Boolean.TRUE.equals(t.getIsActive()), environments.getOrDefault(t.getTeamId(), 0L), 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
