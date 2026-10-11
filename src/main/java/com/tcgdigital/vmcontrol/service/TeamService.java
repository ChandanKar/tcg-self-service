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
    private final com.tcgdigital.vmcontrol.repository.TeamMemberRepository memberRepository;
    private final com.tcgdigital.vmcontrol.repository.UserRepository userRepository;

    public TeamService(TeamRepository teamRepository, AuditService auditService,
                       com.tcgdigital.vmcontrol.repository.TeamMemberRepository memberRepository,
                       com.tcgdigital.vmcontrol.repository.UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.auditService = auditService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    // ============= Membership (E23-T02) =============

    /** A member as the Teams page shows them. */
    public record MemberView(String userId, String displayName, String email, String source, java.sql.Timestamp syncedAt) {}

    /** The teams a user belongs to (for quotas and routing in later epics). */
    @Transactional(readOnly = true)
    public List<String> getTeamIdsForUser(String userId) {
        return memberRepository.findTeamIdsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(String teamId) {
        team(teamId);
        return memberRepository.findByTeamId(teamId).stream().map(m -> {
            com.tcgdigital.vmcontrol.model.User u = userRepository.findById(m.getUserId()).orElse(null);
            return new MemberView(m.getUserId(), u == null ? null : u.getDisplayName(), u == null ? null : u.getEmail(),
                    m.getSource().name(), m.getSyncedAt());
        }).sorted(java.util.Comparator.comparing(v -> v.displayName() == null ? "" : v.displayName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Adds a member by hand; a sync never removes them. */
    @Transactional
    public MemberView addMember(String teamId, String userId, String actorId) {
        Team team = team(teamId);
        com.tcgdigital.vmcontrol.model.User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        com.tcgdigital.vmcontrol.model.TeamMember member = memberRepository
                .findById(new com.tcgdigital.vmcontrol.model.TeamMember.Key(teamId, userId))
                .orElse(new com.tcgdigital.vmcontrol.model.TeamMember(teamId, userId,
                        com.tcgdigital.vmcontrol.model.TeamMember.Source.MANUAL, new java.sql.Timestamp(System.currentTimeMillis())));
        member.setSource(com.tcgdigital.vmcontrol.model.TeamMember.Source.MANUAL);
        memberRepository.save(member);
        auditService.logAction(actorId, AuditAction.TEAM_UPDATED, "team", teamId, team.getDisplayName(),
                "Added member " + (user.getEmail() != null ? user.getEmail() : userId));
        return new MemberView(userId, user.getDisplayName(), user.getEmail(), member.getSource().name(), member.getSyncedAt());
    }

    @Transactional
    public void removeMember(String teamId, String userId, String actorId) {
        Team team = team(teamId);
        com.tcgdigital.vmcontrol.model.TeamMember.Key key = new com.tcgdigital.vmcontrol.model.TeamMember.Key(teamId, userId);
        if (!memberRepository.existsById(key)) {
            throw new ResourceNotFoundException("TeamMember", userId);
        }
        memberRepository.deleteById(key);
        auditService.logAction(actorId, AuditAction.TEAM_UPDATED, "team", teamId, team.getDisplayName(), "Removed member " + userId);
    }

    /** Links (or, with null, unlinks) the team's Entra group. */
    @Transactional
    public TeamDTO linkEntraGroup(String teamId, String groupId, String actorId) {
        Team team = team(teamId);
        String group = groupId == null || groupId.isBlank() ? null : groupId.trim();
        if (group != null) {
            teamRepository.findByEntraGroupId(group).filter(t -> !t.getTeamId().equals(teamId)).ifPresent(t -> {
                throw new ValidationException("That Entra group is already linked to team " + t.getDisplayName());
            });
        }
        team.setEntraGroupId(group);
        teamRepository.save(team);
        auditService.logAction(actorId, AuditAction.TEAM_UPDATED, "team", teamId, team.getDisplayName(),
                group == null ? "Unlinked its Entra group" : "Linked Entra group " + group);
        return toDto(team, environmentCounts());
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
        long members = memberRepository.countByTeam().stream().filter(r -> t.getTeamId().equals(r[0]))
                .mapToLong(r -> ((Number) r[1]).longValue()).findFirst().orElse(0);
        return new TeamDTO(t.getTeamId(), t.getName(), t.getDisplayName(), t.getCostCenter(), t.getEntraGroupId(),
                Boolean.TRUE.equals(t.getIsActive()), environments.getOrDefault(t.getTeamId(), 0L), members);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
