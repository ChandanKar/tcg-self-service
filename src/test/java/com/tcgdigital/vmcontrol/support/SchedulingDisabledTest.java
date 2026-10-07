package com.tcgdigital.vmcontrol.support;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

class SchedulingDisabledTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noScheduledJobsRunInTests() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    void noCloudCallsAtStartup() {
        verifyNoInteractions(awsCloudInventoryProviderService, awsCloudMetricsProviderService, ec2Service,
                computeOptimizerService, costExplorerBillingService, costExplorerTagActivationService,
                reservationCoverageService, graphDirectoryService);
    }

    @Test
    void dataHelpersCreateRowsThatTheResetScriptRemoves() {
        Environment env = newEnvironment("helper-env");
        VmGroup group = newGroup(env, "web");
        Vm vm = newVm(group, "web-1", VmStatus.STOPPED);
        User user = newUser("helper@test.com", false, false);
        grant(user, AccessScopeType.GROUP, group.getGroupId(), AccessLevel.USER);

        assertThat(vmRepository.findById(vm.getVmId())).isPresent();
        assertThat(environmentAccessRepository.findAll())
                .anyMatch(a -> a.getScopeId().equals(group.getGroupId()));
    }
}
