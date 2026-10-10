package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metrics archive works through a backlog in short batches up to a per-run cap, cuts off at
 * a day boundary, and purges archive rows past retention (E12-T01, M17). Against MySQL; this
 * test's samples are older than any other test's, so they are the first to move.
 */
class VmMetricsArchiveServiceTest extends AbstractIntegrationTest {

    @Autowired private VmMetricsArchiveService archiveService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Vm vm;

    @BeforeEach
    void setUp() {
        vm = newVm(newGroup(newEnvironment("Archive"), "app"), "vm", VmStatus.RUNNING);
        ReflectionTestUtils.setField(archiveService, "batchSize", 10);
        ReflectionTestUtils.setField(archiveService, "retentionDays", 0);
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(archiveService, "batchSize", 10000);
        ReflectionTestUtils.setField(archiveService, "maxBatchesPerRun", 200);
        ReflectionTestUtils.setField(archiveService, "retentionDays", 365);
    }

    private void sample(Instant at) {
        jdbcTemplate.update("INSERT INTO vm_metric_sample (metric_sample_id, vm_id, provider, provider_vm_id, sample_time, "
                + "period_seconds, cpu_utilization) VALUES (?, ?, 'AWS', ?, ?, 300, 5)",
                UUID.randomUUID().toString(), vm.getVmId(), vm.getProviderVmId(), Timestamp.from(at));
    }

    private int hot() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vm_metric_sample WHERE vm_id = ?", Integer.class, vm.getVmId());
    }

    private int archived() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vm_metric_sample_archive WHERE vm_id = ?", Integer.class, vm.getVmId());
    }

    private void backlog(int count) {
        Instant oldest = Instant.now().minus(Duration.ofDays(300));
        for (int i = 0; i < count; i++) {
            sample(oldest.plus(Duration.ofMinutes(5L * i)));
        }
    }

    @Test
    void aBacklogMovesInBatchesUntilDone() {
        backlog(45);

        assertThat(archiveService.archiveOldRawSamples()).isGreaterThanOrEqualTo(45);

        assertThat(hot()).isZero();
        assertThat(archived()).isEqualTo(45);
    }

    @Test
    void oneRunStopsAtTheBatchCap() {
        ReflectionTestUtils.setField(archiveService, "maxBatchesPerRun", 2);
        backlog(45);

        assertThat(archiveService.archiveOldRawSamples()).isEqualTo(20);

        assertThat(hot()).isEqualTo(25);
        assertThat(archived()).isEqualTo(20);
    }

    @Test
    void theCutoffIsTheStartOfADay() {
        Instant dayStart = LocalDate.now().minusDays(30).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        sample(dayStart.minus(Duration.ofMinutes(1)));
        sample(dayStart.plus(Duration.ofHours(1))); // same calendar day as the cutoff: stays hot

        archiveService.archiveOldRawSamples();

        assertThat(archived()).isEqualTo(1);
        assertThat(hot()).isEqualTo(1);
    }

    @Test
    void archiveRowsPastRetentionArePurged() {
        ReflectionTestUtils.setField(archiveService, "retentionDays", 365);
        jdbcTemplate.update("INSERT INTO vm_metric_sample_archive (metric_sample_archive_id, vm_id, provider, provider_vm_id, "
                        + "sample_time, period_seconds, created_at) VALUES (?, ?, 'AWS', 'i-old', ?, 300, NOW())",
                UUID.randomUUID().toString(), vm.getVmId(), Timestamp.from(Instant.now().minus(Duration.ofDays(400))));
        jdbcTemplate.update("INSERT INTO vm_metric_sample_archive (metric_sample_archive_id, vm_id, provider, provider_vm_id, "
                        + "sample_time, period_seconds, created_at) VALUES (?, ?, 'AWS', 'i-new', ?, 300, NOW())",
                UUID.randomUUID().toString(), vm.getVmId(), Timestamp.from(Instant.now().minus(Duration.ofDays(100))));

        archiveService.archiveOldRawSamples();

        assertThat(jdbcTemplate.queryForList("SELECT provider_vm_id FROM vm_metric_sample_archive WHERE vm_id = ?",
                String.class, vm.getVmId())).containsExactly("i-new");
    }
}
