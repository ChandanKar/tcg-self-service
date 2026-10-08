package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting a group never destroys VM history, never leaves dangling dependencies, and cleans up
 * what is scoped to the group (E10-T01, M2). Against MySQL (the FK cascade is the risk).
 */
class VmGroupServiceTest extends AbstractIntegrationTest {

    @Autowired private VmGroupService groupService;
    @Autowired private EnvironmentAccessRequestRepository requests;
    @Autowired private AutomationRuleRepository rules;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StateSyncService stateSyncService;

    private Environment env;
    private VmGroup db;
    private VmGroup app;
    private User admin;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Groups");
        db = newGroup(env, "db");
        app = newGroup(env, "app");
        app.setDependencies(List.of(db.getGroupId()));
        app = vmGroupRepository.saveAndFlush(app);
        admin = newUser("group-admin-" + UUID.randomUUID() + "@example.com", true, false);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void deletingAGroupStripsItFromOtherGroupsAndStartOrderStillWorks() {
        groupService.deleteGroup(db.getGroupId(), admin.getUserId());

        assertThat(vmGroupRepository.findById(db.getGroupId())).isEmpty();
        assertThat(vmGroupRepository.findById(app.getGroupId()).orElseThrow().getDependencies()).isEmpty();
        assertThat(groupService.getGroupsInStartOrder(env.getEnvironmentId()))
                .extracting(VmGroup::getGroupId).containsExactly(app.getGroupId());
        awaitAsync(() -> {
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action_type = 'GROUP_DELETED' AND target_id = ? " +
                    "AND user_id = ?", db.getGroupId(), admin.getUserId())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action_type = 'GROUP_DEPENDENCIES_UPDATED' " +
                    "AND target_id = ?", app.getGroupId())).isEqualTo(1);
        });
    }

    @Test
    void aGroupWithActiveVmsIsRefused() {
        newVm(db, "db-1", VmStatus.RUNNING);

        assertThatThrownBy(() -> groupService.deleteGroup(db.getGroupId(), admin.getUserId()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Remove VMs first");
    }

    @Test
    void aGroupWithOnlyRemovedVmsIsRefusedAndTheirHistoryStays() {
        Vm removed = newVm(db, "db-old", VmStatus.STOPPED);
        jdbcTemplate.update("UPDATE vm SET is_active = FALSE, discovery_ignored = TRUE WHERE vm_id = ?", removed.getVmId());
        stateSyncService.recordStateChange(vmRepository.getReferenceById(removed.getVmId()),
                VmStatus.RUNNING, VmStatus.STOPPED, "operation", null, null, "stopped");

        assertThatThrownBy(() -> groupService.deleteGroup(db.getGroupId(), admin.getUserId()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Group has 1 removed VM(s) whose history would be lost; move them to another group first");
        assertThat(vmRepository.findById(removed.getVmId())).isPresent();
        assertThat(count("SELECT COUNT(*) FROM vm_state_history WHERE vm_id = ?", removed.getVmId())).isEqualTo(1);
        assertThat(vmGroupRepository.findById(db.getGroupId())).isPresent();
    }

    @Test
    void grantsRequestsAndRulesScopedToTheGroupAreCleanedUp() {
        User member = newUser("group-member-" + UUID.randomUUID() + "@example.com", false, false);
        EnvironmentAccess grant = grant(member, AccessScopeType.GROUP, db.getGroupId(), AccessLevel.USER);
        EnvironmentAccess envGrant = grant(member, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.VIEWER);
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(env, member, AccessLevel.USER, "need the db group", 7);
        request.setScopeType(AccessScopeType.GROUP);
        request.setScopeId(db.getGroupId());
        request = requests.saveAndFlush(request);
        AutomationRule rule = new AutomationRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setName("Stop db nightly");
        rule.setEnvironment(env);
        rule.setScopeType(AutomationScopeType.GROUP);
        rule.setScopeId(db.getGroupId());
        rule.setTriggerType(AutomationTriggerType.SCHEDULE);
        rule.setDaysOfWeek("MON");
        rule.setStopTime("20:00");
        rule.setTimezone("Asia/Kolkata");
        rule.setEnabled(true);
        rule.setCreatedByUserId(admin.getUserId());
        rule = rules.saveAndFlush(rule);

        groupService.deleteGroup(db.getGroupId(), admin.getUserId());

        assertThat(environmentAccessRepository.findById(grant.getAccessId()).orElseThrow().getStatus()).isEqualTo(AccessStatus.REVOKED);
        assertThat(environmentAccessRepository.findById(envGrant.getAccessId()).orElseThrow().getStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(requests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(AccessRequestStatus.CANCELLED);
        AutomationRule after = rules.findById(rule.getRuleId()).orElseThrow();
        assertThat(after.getEnabled()).isFalse();
        assertThat(after.getDisabledReason()).isEqualTo("Target group no longer exists");
    }

    // ---- E10-T03 (M4): the group name is its identity; metadata edits are a patch ----

    private static com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO edit(VmGroup group, String name) {
        com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO dto = new com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO();
        dto.setName(name);
        dto.setDisplayName("Renamed display");
        dto.setSequencePosition(group.getSequencePosition());
        dto.setDependsOnGroupIds(group.getDependencies());
        return dto;
    }

    @Test
    void aGroupCannotBeRenamed() {
        assertThatThrownBy(() -> groupService.updateGroup(db.getGroupId(), edit(db, "database")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("cannot be changed");
    }

    @Test
    void editingTheDisplayNameKeepsTheNameAndTheMetadata() {
        db.setMetadata("{\"clusterName\":\"MyCluster\",\"nodeGroupName\":\"db\",\"region\":\"eu-west-1\"}");
        db.setDescription("old");
        vmGroupRepository.saveAndFlush(db);
        com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO dto = edit(db, db.getName().toUpperCase());
        dto.setDescription("");

        VmGroup after = groupService.updateGroup(db.getGroupId(), dto);

        assertThat(after.getName()).isEqualTo(db.getName());
        assertThat(after.getDisplayName()).isEqualTo("Renamed display");
        assertThat(after.getMetadata()).contains("\"region\":\"eu-west-1\"", "\"clusterName\":\"MyCluster\"");
        assertThat(after.getDescription()).isNull();
    }
}
