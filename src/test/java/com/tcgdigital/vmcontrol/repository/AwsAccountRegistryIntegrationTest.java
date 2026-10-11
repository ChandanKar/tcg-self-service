package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** V70: one default account; environments and AWS VMs get an account when saved (E28-T03). */
class AwsAccountRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AwsAccountRepository accounts;
    @Autowired private VmRepository vms;
    @Autowired private JdbcTemplate jdbc;

    private String memberRef;

    @AfterEach
    void removeMember() {
        if (memberRef != null) {
            jdbc.update("DELETE FROM vm WHERE aws_account_ref = ?", memberRef);
            jdbc.update("UPDATE environment SET aws_account_ref = ? WHERE aws_account_ref = ?", AwsAccount.DEFAULT_REF, memberRef);
            accounts.deleteById(memberRef);
        }
    }

    @Test
    void exactlyOneDefaultAccountExists() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aws_account WHERE is_default = TRUE", Integer.class)).isEqualTo(1);
        AwsAccount account = accounts.findByIsDefaultTrue().orElseThrow();
        assertThat(account.getAwsAccountRef()).isEqualTo(AwsAccount.DEFAULT_REF);
        assertThat(account.isBillingAccount()).isTrue();
        assertThat(account.getSource()).isEqualTo(AwsAccount.Source.DEFAULT);
    }

    @Test
    void aNewEnvironmentAndItsVmsBelongToTheDefaultAccount() {
        Environment env = newEnvironment("Account default");
        Vm vm = newVm(newGroup(env, "web"), "web-1", VmStatus.STOPPED);

        assertThat(environmentRepository.findById(env.getEnvironmentId()).orElseThrow().getAwsAccountRef())
                .isEqualTo(AwsAccount.DEFAULT_REF);
        assertThat(vms.findById(vm.getVmId()).orElseThrow().getAwsAccountRef()).isEqualTo(AwsAccount.DEFAULT_REF);
    }

    @Test
    void aVmInheritsItsEnvironmentsAccount() {
        AwsAccount member = new AwsAccount();
        member.setDisplayName("Member");
        member.setAccountNumber("210987654321");
        member.setRoleArn("arn:aws:iam::210987654321:role/VmControlAccess");
        member = accounts.saveAndFlush(member);
        memberRef = member.getAwsAccountRef();

        Environment env = new Environment();
        env.setEnvironmentId(java.util.UUID.randomUUID().toString());
        env.setName("member-" + System.nanoTime());
        env.setDisplayName("Member env");
        env.setIsActive(true);
        env.setAwsAccountRef(memberRef);
        env = environmentRepository.saveAndFlush(env);

        // The hook reads the environment when it is loaded (services pass it explicitly).
        com.tcgdigital.vmcontrol.model.VmGroup group = newGroup(env, "app");
        group.setEnvironment(env);
        Vm vm = newVm(group, "app-1", VmStatus.RUNNING);
        assertThat(vm.getProvider()).isEqualTo(CloudProvider.AWS);
        assertThat(vms.findById(vm.getVmId()).orElseThrow().getAwsAccountRef()).isEqualTo(memberRef);
    }
}
