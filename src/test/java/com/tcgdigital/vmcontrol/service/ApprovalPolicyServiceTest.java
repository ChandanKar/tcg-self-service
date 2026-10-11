package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostPreviewDTO;
import com.tcgdigital.vmcontrol.model.ApprovalPolicy;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ApprovalPolicyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Approval policy matching (E25-T03): globs, vCPU, runtime, cost, the off switch and admin bypass. */
class ApprovalPolicyServiceTest {

    private ApprovalPolicyRepository policies;
    private UserRepository users;
    private ApprovalPolicyService service;

    @BeforeEach
    void setUp() {
        policies = mock(ApprovalPolicyRepository.class);
        users = mock(UserRepository.class);
        service = new ApprovalPolicyService(policies, mock(EnvironmentRepository.class), mock(TeamRepository.class), users,
                mock(VmInventorySnapshotRepository.class), mock(OperationCostPreviewService.class), mock(AuditService.class));
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    private static ApprovalPolicy policy(String name, String patterns, Integer vcpu, Integer hours, String cost) {
        ApprovalPolicy p = new ApprovalPolicy();
        p.setPolicyId("p-" + name);
        p.setName(name);
        p.setInstanceTypePatterns(patterns);
        p.setMinVcpu(vcpu);
        p.setMaxRuntimeHours(hours);
        p.setMaxEstimatedCost(cost == null ? null : new BigDecimal(cost));
        return p;
    }

    private static ApprovalPolicyService.ApprovalContext ctx(int hours, String total, ApprovalPolicyService.Target... targets) {
        CostPreviewDTO preview = new CostPreviewDTO(hours, BigDecimal.ONE, new BigDecimal(total), "USD", List.of(), 0, 0, false);
        return new ApprovalPolicyService.ApprovalContext("env-1", "team-1", "user-1", "START", List.of(targets), hours, preview);
    }

    private static ApprovalPolicyService.Target vm(String type, Integer vcpu) {
        return new ApprovalPolicyService.Target("vm-" + type, "vm " + type, type, vcpu);
    }

    @Test
    void globsMatchWholeTypesCaseInsensitively() {
        assertThat(ApprovalPolicyService.globMatches("*.8xlarge", "m5.8xlarge")).isTrue();
        assertThat(ApprovalPolicyService.globMatches("*.8xlarge", "m5.18xlarge")).isFalse();
        assertThat(ApprovalPolicyService.globMatches("p*", "P3.2xlarge")).isTrue();
        assertThat(ApprovalPolicyService.globMatches("m5.?xlarge", "m5.4xlarge")).isTrue();
        assertThat(ApprovalPolicyService.globMatches("m5.large", "m5.large.x")).isFalse();
    }

    @Test
    void anInstanceTypeMatchRequiresApprovalWithTheReason() {
        when(policies.findActiveForEnvironment("env-1", "team-1")).thenReturn(List.of(policy("Big", "*.8xlarge,p*", null, null, null)));
        ApprovalPolicyService.ApprovalDecision decision = service.evaluate(ctx(8, "10.00", vm("m5.large", 2), vm("m5.8xlarge", 32)));
        assertThat(decision.outcome()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(decision.reasons()).containsExactly("m5.8xlarge matches *.8xlarge (vm m5.8xlarge)");
    }

    @Test
    void vcpuRuntimeAndCostThresholds() {
        when(policies.findActiveForEnvironment("env-1", "team-1")).thenReturn(List.of(
                policy("Cores", null, 16, null, null), policy("Long", null, null, 12, null), policy("Pricey", null, null, null, "50.00")));
        ApprovalPolicyService.ApprovalDecision decision = service.evaluate(ctx(24, "75.10", vm("m5.4xlarge", 16)));
        assertThat(decision.matches()).extracting(ApprovalPolicyService.PolicyMatch::name).containsExactly("Cores", "Long", "Pricey");
        assertThat(decision.reasons()).contains("24 h is longer than 12 h", "estimated $75.10 is more than $50.00");

        assertThat(service.evaluate(ctx(12, "50.00", vm("m5.large", 2))).outcome()).isEqualTo("ALLOW");
    }

    @Test
    void offStopsAndAdminBypass() {
        when(policies.findActiveForEnvironment("env-1", "team-1")).thenReturn(List.of(policy("Any", "*", null, null, null)));
        assertThat(service.evaluate(ctx(1, "1", vm("t3.micro", 2))).requiresApproval()).isTrue();

        ApprovalPolicyService.ApprovalContext stop = new ApprovalPolicyService.ApprovalContext("env-1", "team-1", "user-1", "STOP",
                List.of(vm("t3.micro", 2)), 1, null);
        assertThat(service.evaluate(stop).requiresApproval()).isFalse();

        User admin = mock(User.class);
        when(admin.isAdmin()).thenReturn(true);
        when(users.findById(any())).thenReturn(Optional.of(admin));
        assertThat(service.evaluate(ctx(1, "1", vm("t3.micro", 2))).requiresApproval()).isTrue(); // no bypass by default
        ReflectionTestUtils.setField(service, "adminBypass", true);
        assertThat(service.evaluate(ctx(1, "1", vm("t3.micro", 2))).requiresApproval()).isFalse();

        ReflectionTestUtils.setField(service, "adminBypass", false);
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.evaluate(ctx(1, "1", vm("t3.micro", 2))).outcome()).isEqualTo("ALLOW");
    }
}
