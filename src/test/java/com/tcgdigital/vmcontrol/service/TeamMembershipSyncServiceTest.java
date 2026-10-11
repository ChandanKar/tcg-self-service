package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.TeamDTO;
import com.tcgdigital.vmcontrol.exception.DirectoryLookupException;
import com.tcgdigital.vmcontrol.model.TeamMember;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Entra membership sync (E23-T02): known members are added as ENTRA, unknown object ids are
 * skipped, removed members lose their ENTRA row while MANUAL rows stay, and one team's Graph
 * failure does not stop the others.
 */
class TeamMembershipSyncServiceTest extends AbstractIntegrationTest {

    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private UserRepository users;
    @Autowired private TeamService teamService;
    @Autowired private AuditService auditService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final GraphDirectoryService graph = mock(GraphDirectoryService.class);
    private TeamMembershipSyncService sync;
    private String teamId;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        sync = new TeamMembershipSyncService(teams, members, users, graph, auditService, transactionManager);
        teamId = teamService.create("admin-001", new TeamDTO.Request(null, "Platform", null)).teamId();
        teamService.linkEntraGroup(teamId, "group-1", "admin-001");
        alice = newUser("alice@example.com", false, false);
        bob = newUser("bob@example.com", false, false);
    }

    private List<String> memberIds(TeamMember.Source source) {
        return members.findByTeamId(teamId).stream().filter(m -> m.getSource() == source).map(TeamMember::getUserId).toList();
    }

    @Test
    void knownMembersAreAddedAndUnknownOnesSkipped() {
        when(graph.listGroupMemberObjectIds("group-1")).thenReturn(List.of(alice.getAzureAdObjectId(), bob.getAzureAdObjectId(), "oid-nobody"));

        TeamMembershipSyncService.Result result = sync.syncTeam(teamId);

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.skippedUnknown()).isEqualTo(1);
        assertThat(memberIds(TeamMember.Source.ENTRA)).containsExactlyInAnyOrder(alice.getUserId(), bob.getUserId());
        assertThat(teamService.getTeamIdsForUser(alice.getUserId())).containsExactly(teamId);
        assertThat(teamService.get(teamId).memberCount()).isEqualTo(2);
    }

    @Test
    void aRemovedMemberLosesTheirEntraRowButManualRowsStay() {
        User carol = newUser("carol@example.com", false, false);
        teamService.addMember(teamId, carol.getUserId(), "admin-001");
        when(graph.listGroupMemberObjectIds("group-1")).thenReturn(List.of(alice.getAzureAdObjectId(), bob.getAzureAdObjectId()));
        sync.syncTeam(teamId);

        when(graph.listGroupMemberObjectIds("group-1")).thenReturn(List.of(alice.getAzureAdObjectId()));
        TeamMembershipSyncService.Result second = sync.syncTeam(teamId);

        assertThat(second.removed()).isEqualTo(1);
        assertThat(memberIds(TeamMember.Source.ENTRA)).containsExactly(alice.getUserId());
        assertThat(memberIds(TeamMember.Source.MANUAL)).containsExactly(carol.getUserId());
    }

    @Test
    void oneTeamsGraphFailureDoesNotStopTheOthers() {
        String other = teamService.create("admin-001", new TeamDTO.Request(null, "Search", null)).teamId();
        teamService.linkEntraGroup(other, "group-2", "admin-001");
        when(graph.listGroupMemberObjectIds("group-1")).thenThrow(DirectoryLookupException.unavailable("503"));
        when(graph.listGroupMemberObjectIds("group-2")).thenReturn(List.of(bob.getAzureAdObjectId()));

        List<TeamMembershipSyncService.Result> results = sync.syncAll();

        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(r -> r.teamId().equals(teamId)).singleElement()
                .satisfies(r -> assertThat(r.error()).isNotNull());
        assertThat(results).filteredOn(r -> r.teamId().equals(other)).singleElement()
                .satisfies(r -> assertThat(r.added()).isEqualTo(1));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id = 'team_membership_sync'",
                Integer.class)).isEqualTo(1));
    }

    @Test
    void anEntraGroupCanOnlyBeLinkedToOneTeam() {
        String other = teamService.create("admin-001", new TeamDTO.Request(null, "Search", null)).teamId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> teamService.linkEntraGroup(other, "group-1", "admin-001"))
                .hasMessage("That Entra group is already linked to team Platform");
    }
}
