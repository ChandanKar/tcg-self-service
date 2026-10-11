package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.EksWorkloadCostDaily;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.EksWorkloadCostDailyRepository;
import com.tcgdigital.vmcontrol.scheduler.EksSplitCostScheduler;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Split cost rows land in eks_workload_cost_daily under the cluster's environment, and a rerun
 * replaces the day rather than adding to it (E26-T04). Off by default: no scheduler bean.
 */
class EksSplitCostIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EksSplitCostIngestionService ingestion;
    @Autowired private EksWorkloadCostDailyRepository costs;
    @Autowired private ApplicationContext context;

    private Object target;
    private Object realFactory;

    @AfterEach
    void restore() {
        if (target != null) {
            ReflectionTestUtils.setField(target, "enabled", false);
            ReflectionTestUtils.setField(target, "database", "");
            ReflectionTestUtils.setField(target, "table", "");
            ReflectionTestUtils.setField(target, "clientFactory", realFactory);
            ReflectionTestUtils.setField(target, "pollMillis", 2000L);
        }
    }

    private static Row row(String... values) {
        return Row.builder().data(Arrays.stream(values).map(v -> Datum.builder().varCharValue(v).build()).toList()).build();
    }

    private void answer(AthenaClient athena, Row... rows) {
        Mockito.reset(athena);
        when(athena.startQueryExecution(any(StartQueryExecutionRequest.class)))
                .thenReturn(StartQueryExecutionResponse.builder().queryExecutionId("q").build());
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class))).thenReturn(GetQueryExecutionResponse.builder()
                .queryExecution(QueryExecution.builder().status(QueryExecutionStatus.builder().state(QueryExecutionState.SUCCEEDED).build()).build())
                .build());
        Row[] all = new Row[rows.length + 1];
        all[0] = row("usage_date", "cluster", "namespace", "workload", "split_cost", "unused_cost");
        System.arraycopy(rows, 0, all, 1, rows.length);
        when(athena.getQueryResults(any(GetQueryResultsRequest.class)))
                .thenReturn(GetQueryResultsResponse.builder().resultSet(ResultSet.builder().rows(all).build()).build());
    }

    @Test
    void rowsAreStoredUnderTheEnvironmentAndARerunReplacesTheDay() {
        assertThat(context.getBeanNamesForType(EksSplitCostScheduler.class)).isEmpty();

        String cluster = "MyCluster-" + System.nanoTime();
        Environment env = newEnvironment("EKS split");
        env.setServiceType("EKS");
        env.setEksClusterName(cluster);
        environmentRepository.saveAndFlush(env);

        AthenaClient athena = Mockito.mock(AthenaClient.class);
        target = AopTestUtils.getTargetObject(ingestion);
        realFactory = ReflectionTestUtils.getField(target, "clientFactory");
        Function<String, AthenaClient> fake = region -> athena;
        ReflectionTestUtils.setField(target, "clientFactory", fake);
        ReflectionTestUtils.setField(target, "pollMillis", 0L);
        ReflectionTestUtils.setField(target, "enabled", true);
        ReflectionTestUtils.setField(target, "database", "cur");
        ReflectionTestUtils.setField(target, "table", "data_export");

        String day = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        answer(athena, row(day, cluster, "payments", "api", "40", "3"), row(day, cluster, "search", "indexer", "10", "2"));
        assertThat(ingestion.ingest().rowsWritten()).isEqualTo(2);

        answer(athena, row(day, cluster, "payments", "api", "42", "1"));
        assertThat(ingestion.ingest().rowsWritten()).isEqualTo(1);

        Date d = Date.valueOf(day);
        List<EksWorkloadCostDaily> stored = costs.findByEnvironmentIdAndUsageDateBetweenOrderByUsageDateAsc(env.getEnvironmentId(), d, d);
        assertThat(stored).singleElement().satisfies(r -> {
            assertThat(r.getNamespace()).isEqualTo("payments");
            assertThat(r.getSplitCost()).isEqualByComparingTo("42");
            assertThat(r.getUnusedCost()).isEqualByComparingTo("1");
        });
    }
}
