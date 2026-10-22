package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V97 (U19-T04): the backfill gives an environment without a region the one region its active VMs
 * share, and leaves alone stored regions, mixed or missing VM regions, and metadata that is not a
 * JSON object. The migration has run once at startup; the test runs its statement again on rows of
 * its own.
 */
class EnvironmentRegionBackfillIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private String environment(String metadata, String... vmRegions) {
        com.tcgdigital.vmcontrol.model.Environment env = newEnvironment("backfill");
        env.setMetadata(metadata);
        env = environmentRepository.saveAndFlush(env);
        if (vmRegions.length > 0) {
            com.tcgdigital.vmcontrol.model.VmGroup group = newGroup(env, "g");
            int i = 0;
            for (String region : vmRegions) {
                com.tcgdigital.vmcontrol.model.Vm vm = newVm(group, "vm" + (++i), com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);
                vm.setRegion(region);
                vmRepository.saveAndFlush(vm);
            }
        }
        return env.getEnvironmentId();
    }

    private String metadataOf(String id) {
        return jdbc.queryForObject("SELECT metadata FROM environment WHERE environment_id = ?", String.class, id);
    }

    @Test
    void theOneRegionOfAnEnvironmentsVmsIsStoredAndNothingElseChanges() throws Exception {
        String empty = environment(null, "eu-west-1", "eu-west-1");
        String other = environment("{\"owner\": \"payments\"}", "me-central-1");
        String stored = environment("{\"region\": \"ap-south-1\"}", "us-east-1");
        String mixed = environment(null, "ap-south-1", "us-east-1");
        String noVms = environment(null);
        String notJson = environment("legacy text", "eu-west-1");

        String sql = new String(new ClassPathResource("db/migration/V97__backfill_environment_region.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(sql.replaceAll("(?m)^--.*$", ""));

        assertThat(metadataOf(empty)).contains("\"region\"").contains("eu-west-1");
        assertThat(metadataOf(other)).contains("\"owner\"").contains("payments").contains("me-central-1");
        assertThat(metadataOf(stored)).contains("ap-south-1").doesNotContain("us-east-1");
        assertThat(metadataOf(mixed)).isNull();
        assertThat(metadataOf(noVms)).isNull();
        assertThat(metadataOf(notJson)).isEqualTo("legacy text");
    }
}
