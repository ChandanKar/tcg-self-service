package com.tcgdigital.vmcontrol.service.support;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U01-T06: migration V90 renames VMs named after their instance id to the same placeholder discovery uses,
 * and leaves a VM alone when the placeholder is already taken in its group. The statements are run again
 * here against seeded rows (Flyway ran them once on an empty table at startup).
 */
class UnnamedVmMigrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private void runMigration() throws Exception {
        String sql = new ClassPathResource("db/migration/V90__rename_vms_named_after_instance_id.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        StringBuilder statement = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (line.startsWith("--")) continue;
            statement.append(line).append('\n');
            if (line.trim().endsWith(";")) {
                jdbc.update(statement.toString().trim().replaceAll(";$", ""));
                statement.setLength(0);
            }
        }
    }

    private Vm namedAfterItsInstance(VmGroup group) {
        Vm vm = newVm(group, "x", VmStatus.STOPPED);
        vm.setName(vm.getProviderVmId());
        vm.setDisplayName(vm.getProviderVmId());
        return vmRepository.saveAndFlush(vm);
    }

    @Test
    void mysqlAndJavaComputeTheSamePlaceholder() {
        String id = "i-0123456789abcdef0";
        String sql = jdbc.queryForObject("SELECT CONCAT('unnamed-', LEFT(SHA2(?, 256), 6))", String.class, id);

        assertThat(sql).isEqualTo(UnnamedVmNames.placeholderFor(id));
    }

    @Test
    void vmsNamedAfterTheirInstanceAreRenamed() throws Exception {
        Environment env = newEnvironment("V90");
        VmGroup group = newGroup(env, "app");
        Vm vm = namedAfterItsInstance(group);
        Vm named = newVm(group, "web", VmStatus.STOPPED);

        runMigration();

        Vm renamed = vmRepository.findById(vm.getVmId()).orElseThrow();
        String placeholder = UnnamedVmNames.placeholderFor(vm.getProviderVmId());
        assertThat(renamed.getName()).isEqualTo(placeholder);
        assertThat(renamed.getDisplayName()).isEqualTo(placeholder);
        assertThat(vmRepository.findById(named.getVmId()).orElseThrow().getName()).isEqualTo(named.getName());
    }

    @Test
    void aRenameThatWouldClashInItsGroupIsSkipped() throws Exception {
        Environment env = newEnvironment("V90 clash");
        VmGroup group = newGroup(env, "app");
        Vm vm = namedAfterItsInstance(group);
        Vm other = newVm(group, "other", VmStatus.STOPPED);
        other.setName(UnnamedVmNames.placeholderFor(vm.getProviderVmId()));
        vmRepository.saveAndFlush(other);

        runMigration();

        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getName()).isEqualTo(vm.getProviderVmId());
    }
}
