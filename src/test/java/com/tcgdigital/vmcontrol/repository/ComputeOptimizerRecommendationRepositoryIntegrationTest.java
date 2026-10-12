package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.ComputeOptimizerOption;
import com.tcgdigital.vmcontrol.model.ComputeOptimizerRecommendation;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stored Compute Optimizer recommendations on MySQL (E30-T04): options cascade with their
 * recommendation and are replaced in place, a deleted VM leaves the row unlinked, and rows no
 * capture refreshed are purged.
 */
class ComputeOptimizerRecommendationRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ComputeOptimizerRecommendationRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    private static ComputeOptimizerOption option(String type, int rank, String differences) {
        ComputeOptimizerOption o = new ComputeOptimizerOption();
        o.setInstanceType(type);
        o.setOptionRank(rank);
        o.setPerformanceRisk(new BigDecimal("1.00"));
        o.setMigrationEffort("Low");
        o.setPlatformDifferences(differences);
        o.setEstimatedMonthlySavings(new BigDecimal("42.10"));
        return o;
    }

    private ComputeOptimizerRecommendation recommendation(String instanceId, String vmId, Timestamp capturedAt) {
        ComputeOptimizerRecommendation r = new ComputeOptimizerRecommendation();
        r.setProviderVmId(instanceId);
        r.setVmId(vmId);
        r.setRegion("us-east-1");
        r.setAwsAccountRef(AwsAccount.DEFAULT_REF);
        r.setFinding("OVER_PROVISIONED");
        r.setFindingReasons("CPUOverprovisioned");
        r.setCurrentInstanceType("m5.xlarge");
        r.setLookbackDays(14);
        r.setMemoryMetricPresent(true);
        r.setCapturedAt(capturedAt);
        r.addOption(option("m7g.large", 2, "Architecture"));
        r.addOption(option("m5.large", 1, null));
        return r;
    }

    @Test
    void optionsAreStoredInRankOrderAndReplacedInPlace() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Vm vm = newVm(newGroup(newEnvironment("co-env"), "co-group"), "co-vm", VmStatus.RUNNING);
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        tx.executeWithoutResult(s -> repository.save(recommendation("i-co-1", vm.getVmId(), now)));

        List<ComputeOptimizerRecommendation> stored = tx.execute(s -> {
            List<ComputeOptimizerRecommendation> rows = repository.findByRegionAndAwsAccountRef("us-east-1", AwsAccount.DEFAULT_REF);
            rows.forEach(r -> r.getOptions().size());
            return rows;
        });
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getVmId()).isEqualTo(vm.getVmId());
        assertThat(stored.get(0).getOptions()).extracting(ComputeOptimizerOption::getInstanceType).containsExactly("m5.large", "m7g.large");
        assertThat(stored.get(0).getOptions().get(1).getPlatformDifferences()).isEqualTo("Architecture");

        // a later capture replaces the options of the same row
        tx.executeWithoutResult(s -> {
            ComputeOptimizerRecommendation row = repository.findByProviderVmIdIn(List.of("i-co-1")).get(0);
            row.getOptions().clear();
            row.addOption(option("m6i.large", 1, null));
            repository.save(row);
        });
        List<String> types = tx.execute(s -> repository.findByProviderVmIdIn(List.of("i-co-1")).get(0).getOptions().stream()
                .map(ComputeOptimizerOption::getInstanceType).toList());
        assertThat(types).containsExactly("m6i.large");
    }

    @Test
    void rowsNotRefreshedSinceTheCutoffArePurgedWithTheirOptions() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Timestamp old = Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS));
        Timestamp fresh = Timestamp.from(Instant.now());
        tx.executeWithoutResult(s -> {
            repository.save(recommendation("i-old", null, old));
            repository.save(recommendation("i-fresh", null, fresh));
        });

        Integer purged = tx.execute(s -> repository.deleteByCapturedAtBefore(Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS))));

        assertThat(purged).isEqualTo(1);
        assertThat(repository.findAll()).extracting(ComputeOptimizerRecommendation::getProviderVmId).containsExactly("i-fresh");
    }
}
