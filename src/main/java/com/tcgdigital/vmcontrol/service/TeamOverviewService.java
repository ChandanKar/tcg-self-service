package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.TeamOverviewDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.TeamMember;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Teams with their environments, quotas and Entra group (U13-T06). The list is built from a few
 * grouped reads (environments by team, member counts, Entra counts and last sync), with quotas read
 * per team only while quotas are on. A team's environments can be set in one step: listed ones
 * move to it, ones it had that are not listed are left without a team; each move is audited.
 */
@Service
public class TeamOverviewService {

    private final TeamRepository teamRepository;
    private final EnvironmentRepository environmentRepository;
    private final TeamMemberRepository memberRepository;
    private final QuotaService quotaService;
    private final AuditService auditService;

    public TeamOverviewService(TeamRepository teamRepository, EnvironmentRepository environmentRepository,
                               TeamMemberRepository memberRepository, QuotaService quotaService, AuditService auditService) {
        this.teamRepository = teamRepository;
        this.environmentRepository = environmentRepository;
        this.memberRepository = memberRepository;
        this.quotaService = quotaService;
        this.auditService = auditService;
    }

    /** Every active team. */
    @Transactional(readOnly = true)
    public List<TeamOverviewDTO> overview() {
        return build(teamRepository.findByIsActiveTrueOrderByDisplayName());
    }

    @Transactional(readOnly = true)
    public TeamOverviewDTO overview(String teamId) {
        return build(List.of(team(teamId))).get(0);
    }

    private List<TeamOverviewDTO> build(List<Team> teams) {
        Map<String, List<TeamOverviewDTO.Environment>> environments = new HashMap<>();
        environmentRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .filter(e -> e.getTeam() != null)
                .sorted(Comparator.comparing(TeamOverviewService::envName, String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> environments.computeIfAbsent(e.getTeam().getTeamId(), k -> new ArrayList<>())
                        .add(new TeamOverviewDTO.Environment(e.getEnvironmentId(), e.getName(), envName(e), Boolean.TRUE.equals(e.getIsProduction()))));
        Map<String, Long> members = new HashMap<>();
        memberRepository.countByTeam().forEach(r -> members.put((String) r[0], ((Number) r[1]).longValue()));
        Map<String, Object[]> entra = new HashMap<>();
        memberRepository.countAndLastSyncBySource(TeamMember.Source.ENTRA).forEach(r -> entra.put((String) r[0], r));
        boolean quotas = quotaService.isEnabled();
        return teams.stream().map(t -> {
            Object[] e = entra.get(t.getTeamId());
            return new TeamOverviewDTO(t.getTeamId(), t.getName(), t.getDisplayName(), t.getCostCenter(),
                    Boolean.TRUE.equals(t.getIsActive()), members.getOrDefault(t.getTeamId(), 0L),
                    t.getEntraGroupId(), t.getEntraGroupName(),
                    e == null ? 0 : ((Number) e[1]).longValue(), e == null ? null : (Timestamp) e[2],
                    environments.getOrDefault(t.getTeamId(), List.of()),
                    quotas ? quotaService.usage(t.getTeamId()) : List.of());
        }).toList();
    }

    /** The team's environments become exactly these (active ones); the others it had are left without a team. */
    @Transactional
    public TeamOverviewDTO setEnvironments(String teamId, List<String> environmentIds, String actorId) {
        Team team = team(teamId);
        if (!Boolean.TRUE.equals(team.getIsActive())) {
            throw new ValidationException("Team " + team.getDisplayName() + " is deactivated");
        }
        Set<String> wanted = new LinkedHashSet<>();
        (environmentIds == null ? List.<String>of() : environmentIds).stream().filter(Objects::nonNull)
                .map(String::trim).filter(id -> !id.isEmpty()).forEach(wanted::add);
        List<Environment> moving = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String id : wanted) {
            Environment e = environmentRepository.findById(id).orElse(null);
            if (e == null) {
                problems.add("unknown environment " + id);
            } else if (!Boolean.TRUE.equals(e.getIsActive())) {
                problems.add(envName(e) + " is deactivated");
            } else {
                moving.add(e);
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("Cannot set the environments: " + String.join(", ", problems));
        }
        for (Environment e : environmentRepository.findByIsActiveTrueOrderByNameAsc()) {
            if (e.getTeam() != null && teamId.equals(e.getTeam().getTeamId()) && !wanted.contains(e.getEnvironmentId())) {
                e.setTeam(null);
                environmentRepository.save(e);
                auditService.logAction(actorId, AuditAction.TEAM_UPDATED, "team", teamId, team.getDisplayName(),
                        "Removed environment " + envName(e));
            }
        }
        for (Environment e : moving) {
            Team before = e.getTeam();
            if (before != null && teamId.equals(before.getTeamId())) {
                continue;
            }
            e.setTeam(team);
            environmentRepository.save(e);
            auditService.logAction(actorId, AuditAction.TEAM_UPDATED, "team", teamId, team.getDisplayName(),
                    "Added environment " + envName(e) + (before == null ? "" : " (from team " + before.getDisplayName() + ")"));
        }
        environmentRepository.flush();
        return overview(teamId);
    }

    private Team team(String teamId) {
        return teamRepository.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
    }

    private static String envName(Environment e) {
        return e.getDisplayName() != null && !e.getDisplayName().isBlank() ? e.getDisplayName() : e.getName();
    }

}
