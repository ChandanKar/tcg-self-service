package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessGrantMode;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationRunReason;
import com.tcgdigital.vmcontrol.model.AutomationRunStatus;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Automation hooks never roll back the lock or grant that triggered them, and "nothing to do"
 * is a normal outcome (E06-T02, C5). Not @Transactional: the hooks run after commit.
 */
class AutomationRuleHookTransactionTest extends AbstractIntegrationTest {

    @Autowired private LockService lockService;
    @Autowired private EnvironmentAccessService accessService;
    @Autowired private AutomationRuleRepository rules;
    @Autowired private EnvironmentLockRepository locks;
    @Autowired private EnvironmentAccessRequestRepository requests;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private PlatformTransactionManager transactionManager;

    private Environment env;
    private User user;
    private User admin;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Hooks");
        user = newUser("hook-user@example.com", false, false);
        admin = newUser("hook-admin@example.com", true, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenReturn(CompletableFuture
                .completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING)));
    }

    private AutomationRule rule(AccessGrantMode mode, AutomationScopeType scope, String scopeId) {
        AutomationRule rule = new AutomationRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setName("Start on " + mode);
        rule.setEnvironment(env);
        rule.setScopeType(scope);
        rule.setScopeId(scopeId);
        rule.setTriggerType(AutomationTriggerType.ACCESS_GRANT);
        rule.setAccessGrantMode(mode);
        rule.setSkipIfAlreadyInTargetState(true);
        rule.setEnabled(true);
        rule.setCreatedByUserId(admin.getUserId());
        return rules.saveAndFlush(rule);
    }

    private AutomationRule reload(AutomationRule rule) {
        return rules.findById(rule.getRuleId()).orElseThrow();
    }

    @Test
    void lockIsAcquiredAndTheRuleRecordsNothingToDoWhenEveryVmIsAlreadyRunning() {
        VmGroup group = newGroup(env, "app");
        newVm(group, "up-1", VmStatus.RUNNING);
        newVm(group, "up-2", VmStatus.RUNNING);
        AutomationRule rule = rule(AccessGrantMode.LOCK_ACQUIRE, AutomationScopeType.ENVIRONMENT, null);

        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "release work", null);

        assertThat(locks.findByEnvironmentEnvironmentIdAndIsActiveTrue(env.getEnvironmentId())).isPresent();
        AutomationRule after = reload(rule);
        assertThat(after.getLastRunStatus()).isEqualTo(AutomationRunStatus.SKIPPED);
        assertThat(after.getLastRunDetail()).isEqualTo("Nothing to do: all targets already in the requested state");
        assertThat(executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId())).isEmpty();
    }

    @Test
    void lockAcquireRuleStartsStoppedVmsAsTheLockHolder() {
        newVm(newGroup(env, "app"), "down-1", VmStatus.STOPPED);
        AutomationRule rule = rule(AccessGrantMode.LOCK_ACQUIRE, AutomationScopeType.ENVIRONMENT, null);

        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "release work", null);

        assertThat(reload(rule).getLastRunStatus()).isEqualTo(AutomationRunStatus.SUCCESS);
        assertThat(executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId()))
                .singleElement()
                .satisfies(e -> assertThat(e.getInitiatedByUserId()).isEqualTo(user.getUserId()));
    }

    @Test
    void anApprovalIsKeptWhenItsRuleFailsToStart() {
        // A VM-scoped rule whose VM no longer exists: the rule cannot act and is switched off.
        AutomationRule rule = rule(AccessGrantMode.ACCESS_APPROVED, AutomationScopeType.VM, "vm-that-was-deleted");
        User requester = newUser("hook-requester@example.com", false, false);
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need access for the release");
        EnvironmentAccessRequest request = accessService.createAccessRequest(env.getEnvironmentId(),
                requester.getUserId(), dto);

        accessService.approveRequest(request.getRequestId(), admin.getUserId(), null, null);

        assertThat(requests.findById(request.getRequestId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(environmentAccessRepository.findAll())
                .filteredOn(ea -> ea.getUser().getUserId().equals(requester.getUserId()))
                .singleElement()
                .satisfies(ea -> assertThat(ea.getStatus()).isEqualTo(AccessStatus.ACTIVE));
        // The rule's outcome is recorded too (it is written outside the approval's transaction).
        AutomationRule after = reload(rule);
        assertThat(after.getLastRunReason()).isEqualTo(AutomationRunReason.SCOPE_MISSING);
        assertThat(after.getEnabled()).isFalse();
    }

    @Test
    void noRuleFiresWhenTheAcquiringTransactionRollsBack() {
        newVm(newGroup(env, "app"), "down-1", VmStatus.STOPPED);
        AutomationRule rule = rule(AccessGrantMode.LOCK_ACQUIRE, AutomationScopeType.ENVIRONMENT, null);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "release work", null);
            status.setRollbackOnly();
        });

        assertThat(locks.findByEnvironmentEnvironmentIdAndIsActiveTrue(env.getEnvironmentId())).isEmpty();
        assertThat(reload(rule).getLastRunAt()).isNull();
        assertThat(executions.findTop20ByEnvironmentEnvironmentIdOrderByStartedAtDesc(env.getEnvironmentId())).isEmpty();
    }
}
