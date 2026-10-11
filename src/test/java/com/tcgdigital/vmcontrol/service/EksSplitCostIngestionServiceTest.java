package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.EksWorkloadCostDaily;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.EksWorkloadCostDailyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Split Cost Allocation Data ingestion through Athena (E26-T04), with a mocked AthenaClient. */
class EksSplitCostIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:30:00Z");

    private AthenaClient athena;
    private EksWorkloadCostDailyRepository costs;
    private AuditService audit;
    private EksSplitCostIngestionService service;
    private final AtomicInteger clientsBuilt = new AtomicInteger();

    @BeforeEach
    void setUp() {
        athena = mock(AthenaClient.class);
        costs = mock(EksWorkloadCostDailyRepository.class);
        audit = mock(AuditService.class);
        EnvironmentRepository environments = mock(EnvironmentRepository.class);
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("prod");
        env.setEksClusterName("MyCluster");
        when(environments.findActiveEksEnvironments()).thenReturn(List.of(env));

        com.tcgdigital.vmcontrol.service.aws.AwsClientFactory factory = mock(com.tcgdigital.vmcontrol.service.aws.AwsClientFactory.class);
        when(factory.isConfigured()).thenReturn(true);
        service = new EksSplitCostIngestionService(environments, costs, audit, mock(PlatformTransactionManager.class), factory);
        service.clientFactory = region -> {
            clientsBuilt.incrementAndGet();
            return athena;
        };
        service.pollMillis = 0;
        service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "database", "cur");
        ReflectionTestUtils.setField(service, "table", "data_export");
        when(athena.startQueryExecution(any(StartQueryExecutionRequest.class)))
                .thenReturn(StartQueryExecutionResponse.builder().queryExecutionId("q-1").build());
    }

    private static GetQueryExecutionResponse state(QueryExecutionState state, String reason) {
        return GetQueryExecutionResponse.builder().queryExecution(QueryExecution.builder()
                .status(QueryExecutionStatus.builder().state(state).stateChangeReason(reason).build()).build()).build();
    }

    private static Row row(String... values) {
        return Row.builder().data(Arrays.stream(values).map(v -> Datum.builder().varCharValue(v).build()).toList()).build();
    }

    private static GetQueryResultsResponse page(String next, Row... rows) {
        return GetQueryResultsResponse.builder().nextToken(next).resultSet(ResultSet.builder().rows(rows).build()).build();
    }

    @Test
    void pollsUntilDoneReadsEveryPageAndSumsRowsUnderTheClustersEnvironment() {
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class)))
                .thenReturn(state(QueryExecutionState.QUEUED, null), state(QueryExecutionState.RUNNING, null),
                        state(QueryExecutionState.SUCCEEDED, null));
        when(athena.getQueryResults(any(GetQueryResultsRequest.class))).thenReturn(
                page("t2",
                        row("usage_date", "cluster", "namespace", "workload", "split_cost", "unused_cost"),
                        row("2026-10-11", "MyCluster", "payments", "api", "30.5", "2"),
                        row("2026-10-11", "OtherCluster", "web", "front", "7", "0")),
                page(null,
                        row("2026-10-11", "MyCluster", "payments", "api", "9.5", "1"),
                        row("2026-10-11", "MyCluster", "search", "", "10", "0")));

        EksSplitCostIngestionService.IngestResult result = service.ingest();

        assertThat(result.status()).isEqualTo(EksSplitCostIngestionService.Status.OK);
        assertThat(result.rowsWritten()).isEqualTo(2);
        assertThat(result.rowsSkipped()).isEqualTo(1);
        assertThat(result.unknownClusters()).isEqualTo(Set.of("OtherCluster"));
        verify(athena, times(3)).getQueryExecution(any(GetQueryExecutionRequest.class));

        ArgumentCaptor<GetQueryResultsRequest> pages = ArgumentCaptor.forClass(GetQueryResultsRequest.class);
        verify(athena, times(2)).getQueryResults(pages.capture());
        assertThat(pages.getAllValues().get(1).nextToken()).isEqualTo("t2");

        verify(costs).deleteDay("env-1", Date.valueOf("2026-10-11"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<EksWorkloadCostDaily>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(costs).saveAll(saved.capture());
        List<EksWorkloadCostDaily> rows = new ArrayList<>();
        saved.getValue().forEach(rows::add);
        assertThat(rows).extracting(EksWorkloadCostDaily::getNamespace, EksWorkloadCostDaily::getWorkload)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("payments", "api"), org.assertj.core.groups.Tuple.tuple("search", "(none)"));
        assertThat(rows.get(0).getSplitCost()).isEqualByComparingTo("40.0");
        assertThat(rows.get(0).getUnusedCost()).isEqualByComparingTo("3");
        assertThat(rows.get(1).getSplitCost()).isEqualByComparingTo("10");

        verify(audit).logAction(isNull(), eq(AuditAction.SCHEDULED_JOB_EXECUTED), eq("job"), eq("eks_split_cost"), any(),
                contains("2 row(s) written, 1 skipped"));
    }

    @Test
    void theQueryCoversTheLookbackDaysAsParametersOverTheConfiguredTable() {
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class))).thenReturn(state(QueryExecutionState.SUCCEEDED, null));
        when(athena.getQueryResults(any(GetQueryResultsRequest.class))).thenReturn(page(null, row("h1", "h2", "h3", "h4", "h5", "h6")));

        service.ingest();

        ArgumentCaptor<StartQueryExecutionRequest> start = ArgumentCaptor.forClass(StartQueryExecutionRequest.class);
        verify(athena).startQueryExecution(start.capture());
        assertThat(start.getValue().executionParameters()).containsExactly("'2026-10-09 00:00:00'", "'2026-10-12 00:00:00'");
        assertThat(start.getValue().queryExecutionContext().database()).isEqualTo("cur");
        assertThat(start.getValue().queryString())
                .contains("FROM \"data_export\"")
                .contains("split_line_item_split_cost")
                .contains("split_line_item_unused_cost")
                .contains("element_at(resource_tags, 'aws_eks_workload_name')")
                .contains("LIKE 'arn:aws:eks:%:pod/%'");
        verify(costs, never()).saveAll(any());
    }

    @Test
    void aMissingTableIsReportedAsNotEnabledAndNothingIsWritten() {
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class)))
                .thenReturn(state(QueryExecutionState.FAILED, "TABLE_NOT_FOUND: line 7:6: Table 'awsdatacatalog.cur.data_export' does not exist"));

        EksSplitCostIngestionService.IngestResult result = service.ingest();

        assertThat(result.status()).isEqualTo(EksSplitCostIngestionService.Status.NOT_ENABLED);
        assertThat(result.message()).contains("Split Cost Allocation Data not enabled in Data Exports");
        verify(athena, never()).getQueryResults(any(GetQueryResultsRequest.class));
        verifyNoInteractions(costs);
    }

    @Test
    void aFailedQueryLogsTheAthenaErrorAndChangesNoRows() {
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class)))
                .thenReturn(state(QueryExecutionState.FAILED, "Access denied when writing to location: s3://results/"));

        EksSplitCostIngestionService.IngestResult result = service.ingest();

        assertThat(result.status()).isEqualTo(EksSplitCostIngestionService.Status.FAILED);
        assertThat(result.message()).contains("Access denied");
        verifyNoInteractions(costs);
        verify(audit).logAction(isNull(), eq(AuditAction.SCHEDULED_JOB_EXECUTED), any(), any(), any(), contains("FAILED"));
    }

    @Test
    void offOrUnconfiguredNoAthenaClientIsBuilt() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.ingest().status()).isEqualTo(EksSplitCostIngestionService.Status.DISABLED);

        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "table", "x; DROP TABLE y");
        assertThat(service.ingest().status()).isEqualTo(EksSplitCostIngestionService.Status.FAILED);

        assertThat(clientsBuilt.get()).isZero();
        verifyNoInteractions(athena, costs);
    }
}
