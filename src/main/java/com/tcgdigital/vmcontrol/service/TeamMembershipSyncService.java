package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.TeamMember;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Syncs team membership from each team's Entra group (E23-T02). Members are matched to app users
 * by Entra object id; a member without an app user is skipped (counted, never created). Only
 * ENTRA rows are added or removed; MANUAL rows are left alone. Each team syncs in its own
 * transaction, so one team's Graph failure does not stop the others.
 */
@Service
public class TeamMembershipSyncService {

    private static final Logger log = LoggerFactory.getLogger(TeamMembershipSyncService.class);

    /** One team's sync. */
    public record Result(String teamId, int members, int added, int removed, int skippedUnknown, String error) {}

    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final GraphDirectoryService graph;
    private final AuditService auditService;
    private final TransactionTemplate perTeam;

    public TeamMembershipSyncService(TeamRepository teamRepository, TeamMemberRepository memberRepository,
                                     UserRepository userRepository, GraphDirectoryService graph, AuditService auditService,
                                     PlatformTransactionManager transactionManager) {
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.graph = graph;
        this.auditService = auditService;
        this.perTeam = new TransactionTemplate(transactionManager);
        this.perTeam.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isAvailable() {
        return graph.isEnabled();
    }

    /** Every team linked to a group; returns each team's result. */
    public List<Result> syncAll() {
        List<Result> results = new ArrayList<>();
        for (Team team : teamRepository.findAll()) {
            if (team.getEntraGroupId() == null || !Boolean.TRUE.equals(team.getIsActive())) {
                continue;
            }
            try {
                results.add(syncTeam(team.getTeamId()));
            } catch (Exception e) {
                log.warn("Team {} membership sync failed: {}", team.getName(), e.getMessage());
                results.add(new Result(team.getTeamId(), 0, 0, 0, 0, e.getMessage()));
            }
        }
        int added = results.stream().mapToInt(Result::added).sum();
        int removed = results.stream().mapToInt(Result::removed).sum();
        long failed = results.stream().filter(r -> r.error() != null).count();
        try {
            auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "job", "team_membership_sync", "Team membership sync",
                    results.size() + " team(s): " + added + " added, " + removed + " removed, " + failed + " failed");
        } catch (Exception e) {
            log.warn("Could not audit the team membership sync: {}", e.getMessage());
        }
        return results;
    }

    /** One team, in its own transaction. Throws when Graph fails (nothing is changed then). */
    public Result syncTeam(String teamId) {
        Team team = teamRepository.findById(teamId).orElseThrow();
        if (team.getEntraGroupId() == null) {
            return new Result(teamId, 0, 0, 0, 0, null);
        }
        List<String> objectIds = graph.listGroupMemberObjectIds(team.getEntraGroupId()); // before any change
        return perTeam.execute(status -> apply(team, objectIds));
    }

    private Result apply(Team team, List<String> objectIds) {
        Timestamp now = Timestamp.from(Instant.now());
        Set<String> wanted = new HashSet<>();
        int skipped = 0;
        for (String objectId : new HashSet<>(objectIds)) {
            User user = userRepository.findByAzureAdObjectId(objectId).orElse(null);
            if (user == null) {
                skipped++;
            } else {
                wanted.add(user.getUserId());
            }
        }
        Map<String, TeamMember> current = memberRepository.findByTeamId(team.getTeamId()).stream()
                .collect(Collectors.toMap(TeamMember::getUserId, Function.identity()));
        int added = 0;
        int removed = 0;
        for (String userId : wanted) {
            TeamMember existing = current.get(userId);
            if (existing == null) {
                memberRepository.save(new TeamMember(team.getTeamId(), userId, TeamMember.Source.ENTRA, now));
                added++;
            } else if (existing.getSource() == TeamMember.Source.ENTRA) {
                existing.setSyncedAt(now);
                memberRepository.save(existing);
            }
        }
        for (TeamMember member : current.values()) {
            if (member.getSource() == TeamMember.Source.ENTRA && !wanted.contains(member.getUserId())) {
                memberRepository.delete(member);
                removed++;
            }
        }
        return new Result(team.getTeamId(), wanted.size(), added, removed, skipped, null);
    }
}
