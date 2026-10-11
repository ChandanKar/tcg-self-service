package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CostAnomalyMonitor;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostAnomalyMonitorRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.AnomalyMonitor;
import software.amazon.awssdk.services.costexplorer.model.CreateAnomalyMonitorRequest;
import software.amazon.awssdk.services.costexplorer.model.CreateAnomalyMonitorResponse;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.GetAnomalyMonitorsRequest;
import software.amazon.awssdk.services.costexplorer.model.GetAnomalyMonitorsResponse;
import software.amazon.awssdk.services.costexplorer.model.LimitExceededException;
import software.amazon.awssdk.services.costexplorer.model.MonitorType;
import software.amazon.awssdk.services.costexplorer.model.TagValues;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Anomaly monitors (E21-T01): pages of monitors are stored, a tag monitor on one environment
 * links itself, manual links stick, and monitors are only created with manage-monitors on (a
 * reached quota stops the run with a warning).
 */
class CostAnomalyMonitorServiceTest extends AbstractIntegrationTest {

    @Autowired private CostAnomalyMonitorRepository monitors;
    @Autowired private EnvironmentRepository environments;

    private final CostExplorerClient ce = mock(CostExplorerClient.class);
    private Environment prod;

    @BeforeEach
    void setUp() {
        prod = newEnvironment("prod");
    }

    private CostAnomalyMonitorService service(boolean manage) {
        return new CostAnomalyMonitorService(monitors, environments, new CostExplorerClientProvider(ce), manage, "tcg:");
    }

    private static AnomalyMonitor tagMonitor(String arn, String... values) {
        return AnomalyMonitor.builder().monitorArn(arn).monitorName("m-" + arn).monitorType(MonitorType.CUSTOM)
                .monitorSpecification(Expression.builder().tags(TagValues.builder().key("tcg:environment").values(values).build()).build())
                .build();
    }

    private void awsHas(AnomalyMonitor... firstPage) {
        AnomalyMonitor service = AnomalyMonitor.builder().monitorArn("arn:svc").monitorName("Services")
                .monitorType(MonitorType.DIMENSIONAL).build();
        when(ce.getAnomalyMonitors(argThat((GetAnomalyMonitorsRequest r) -> r != null && r.nextPageToken() == null)))
                .thenReturn(GetAnomalyMonitorsResponse.builder().anomalyMonitors(firstPage).nextPageToken("p2").build());
        when(ce.getAnomalyMonitors(argThat((GetAnomalyMonitorsRequest r) -> r != null && "p2".equals(r.nextPageToken()))))
                .thenReturn(GetAnomalyMonitorsResponse.builder().anomalyMonitors(service).build());
    }

    @Test
    void aTagMonitorOnOneEnvironmentLinksItselfAcrossPages() {
        awsHas(tagMonitor("arn:prod", prod.getName()), tagMonitor("arn:two", prod.getName(), "other"));

        CostAnomalyMonitorService.SyncResult result = service(false).sync();

        assertThat(result.monitors()).isEqualTo(3);
        assertThat(result.linked()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(monitors.findById("arn:prod").orElseThrow().getEnvironmentId()).isEqualTo(prod.getEnvironmentId());
        assertThat(monitors.findById("arn:two").orElseThrow().getEnvironmentId()).isNull(); // two values: ambiguous
        assertThat(monitors.findById("arn:svc").orElseThrow().getMonitorType()).isEqualTo("DIMENSIONAL");
        verify(ce, never()).createAnomalyMonitor(any(CreateAnomalyMonitorRequest.class));
    }

    @Test
    void aManualLinkSticksThroughASync() {
        awsHas(tagMonitor("arn:prod", prod.getName()));
        CostAnomalyMonitorService service = service(false);
        service.sync();
        Environment other = newEnvironment("finops");
        service.link("arn:svc", other.getEnvironmentId());
        service.link("arn:prod", null); // unlinked by hand

        service.sync();

        assertThat(monitors.findById("arn:svc").orElseThrow().getEnvironmentId()).isEqualTo(other.getEnvironmentId());
        assertThat(monitors.findById("arn:prod").orElseThrow().getEnvironmentId()).isNull();
        assertThat(service.list()).extracting(CostAnomalyMonitorService.MonitorView::linkedManually).contains(true);
    }

    @Test
    void withManageMonitorsEachUnwatchedEnvironmentGetsATagMonitor() {
        newEnvironment("qa");
        newEnvironment("dev");
        awsHas(tagMonitor("arn:prod", prod.getName()));
        when(ce.createAnomalyMonitor(any(CreateAnomalyMonitorRequest.class))).thenAnswer(inv -> CreateAnomalyMonitorResponse.builder()
                .monitorArn("arn:new:" + ((CreateAnomalyMonitorRequest) inv.getArgument(0)).anomalyMonitor().monitorName()).build());

        CostAnomalyMonitorService.SyncResult result = service(true).sync();

        long unwatched = environments.findByIsActiveTrue().size() - 1; // every active environment but prod
        assertThat(result.created()).isEqualTo((int) unwatched);
        verify(ce, times((int) unwatched)).createAnomalyMonitor(argThat((CreateAnomalyMonitorRequest r) ->
                r.anomalyMonitor().monitorType() == MonitorType.CUSTOM
                        && r.anomalyMonitor().monitorSpecification().tags().key().equals("tcg:environment")));
        assertThat(monitors.findAll()).filteredOn(CostAnomalyMonitor::getManaged).hasSize((int) unwatched)
                .allSatisfy(m -> assertThat(m.getEnvironmentId()).isNotNull());
    }

    @Test
    void aReachedQuotaStopsWithAWarning() {
        awsHas();
        when(ce.createAnomalyMonitor(any(CreateAnomalyMonitorRequest.class)))
                .thenThrow(LimitExceededException.builder().message("quota").build());

        CostAnomalyMonitorService.SyncResult result = service(true).sync();

        assertThat(result.created()).isZero();
        assertThat(result.warning()).contains("quota");
    }

    @Test
    void withoutCredentialsNothingIsCalled() {
        CostAnomalyMonitorService.SyncResult result = new CostAnomalyMonitorService(monitors, environments,
                CostExplorerClientProvider.unconfigured(), true, "tcg:").sync();
        assertThat(result.warning()).isEqualTo("AWS credentials are not configured");
        assertThat(List.of(result.monitors(), result.created())).containsOnly(0);
    }
}
