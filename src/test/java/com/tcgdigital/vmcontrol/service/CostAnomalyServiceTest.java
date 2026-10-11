package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CostAnomaly;
import com.tcgdigital.vmcontrol.model.CostAnomalyMonitor;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostAnomalyMonitorRepository;
import com.tcgdigital.vmcontrol.repository.CostAnomalyRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.Anomaly;
import software.amazon.awssdk.services.costexplorer.model.AnomalyScore;
import software.amazon.awssdk.services.costexplorer.model.GetAnomaliesRequest;
import software.amazon.awssdk.services.costexplorer.model.GetAnomaliesResponse;
import software.amazon.awssdk.services.costexplorer.model.Impact;
import software.amazon.awssdk.services.costexplorer.model.RootCause;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Anomaly ingestion (E21-T02): pages are followed, each anomaly takes its monitor's environment
 * (none for an unlinked monitor), and a second run updates in place, keeping the app's notified
 * time and feedback.
 */
class CostAnomalyServiceTest extends AbstractIntegrationTest {

    @Autowired private CostAnomalyRepository anomalies;
    @Autowired private CostAnomalyMonitorRepository monitors;
    @Autowired private ObjectMapper objectMapper;

    private final CostExplorerClient ce = mock(CostExplorerClient.class);
    private CostAnomalyService service;
    private Environment prod;

    @BeforeEach
    void setUp() {
        prod = newEnvironment("prod");
        CostAnomalyMonitor linked = new CostAnomalyMonitor();
        linked.setMonitorArn("arn:prod");
        linked.setMonitorName("tcg-env-prod");
        linked.setEnvironmentId(prod.getEnvironmentId());
        monitors.saveAndFlush(linked);
        service = new CostAnomalyService(anomalies, monitors, new CostExplorerClientProvider(ce), objectMapper, 7);
    }

    private static Anomaly anomaly(String id, String monitorArn, double impact) {
        return Anomaly.builder().anomalyId(id).monitorArn(monitorArn)
                .anomalyStartDate("2026-10-08T00:00:00Z").anomalyEndDate("2026-10-09")
                .impact(Impact.builder().maxImpact(impact).totalImpact(impact).totalExpectedSpend(20.0).build())
                .anomalyScore(AnomalyScore.builder().maxScore(87.5).currentScore(80.0).build())
                .rootCauses(RootCause.builder().service("Amazon Elastic Compute Cloud - Compute").region("ap-south-1")
                        .usageType("BoxUsage:m5.large").linkedAccount("123456789012").build())
                .build();
    }

    private void awsReturns(double impact) {
        when(ce.getAnomalies(argThat((GetAnomaliesRequest r) -> r != null && r.nextPageToken() == null)))
                .thenReturn(GetAnomaliesResponse.builder().anomalies(anomaly("a-1", "arn:prod", impact)).nextPageToken("p2").build());
        when(ce.getAnomalies(argThat((GetAnomaliesRequest r) -> r != null && "p2".equals(r.nextPageToken()))))
                .thenReturn(GetAnomaliesResponse.builder().anomalies(anomaly("a-2", "arn:unlinked", 5.0)).build());
    }

    @Test
    void twoPagesGiveTwoRowsWithTheirEnvironments() {
        awsReturns(140.0);

        CostAnomalyService.IngestResult result = service.ingest();

        assertThat(result.seen()).isEqualTo(2);
        assertThat(result.added()).isEqualTo(2);
        CostAnomaly a1 = anomalies.findById("a-1").orElseThrow();
        assertThat(a1.getEnvironmentId()).isEqualTo(prod.getEnvironmentId());
        assertThat(a1.getAnomalyStart()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(a1.getAnomalyEnd()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(a1.getTotalImpact()).isEqualByComparingTo("140.00");
        assertThat(a1.getTotalExpected()).isEqualByComparingTo("20.00");
        assertThat(a1.getMaxScore()).isEqualByComparingTo("87.50");
        assertThat(a1.getRootCauses()).contains("\"usageType\":\"BoxUsage:m5.large\"");
        assertThat(anomalies.findById("a-2").orElseThrow().getEnvironmentId()).isNull(); // unlinked monitor
    }

    @Test
    void aSecondRunUpdatesAndKeepsNotifiedAndFeedback() {
        awsReturns(140.0);
        service.ingest();
        CostAnomaly a1 = anomalies.findById("a-1").orElseThrow();
        Timestamp notified = Timestamp.from(Instant.parse("2026-10-09T06:00:00Z"));
        a1.setNotifiedAt(notified);
        a1.setFeedback("YES");
        anomalies.saveAndFlush(a1);

        awsReturns(210.0);
        CostAnomalyService.IngestResult second = service.ingest();

        assertThat(second.added()).isZero();
        assertThat(anomalies.count()).isEqualTo(2);
        CostAnomaly updated = anomalies.findById("a-1").orElseThrow();
        assertThat(updated.getTotalImpact()).isEqualByComparingTo("210.00");
        assertThat(updated.getNotifiedAt()).isEqualTo(notified);
        assertThat(updated.getFeedback()).isEqualTo("YES");
    }

    @Test
    void withoutCredentialsNothingIsCalled() {
        CostAnomalyService off = new CostAnomalyService(anomalies, monitors, new CostExplorerClientProvider("", ""),
                objectMapper, 7);
        assertThat(off.ingest().seen()).isZero();
    }
}
