package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** CloudWatch metrics of RDS databases (E33-T03): the queries, the mapping, and nothing while off. */
@ExtendWith(MockitoExtension.class)
class RdsCloudMetricsProviderServiceTest {

    private static final String REGION = "ap-south-1";
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");

    @Mock private AwsClientFactory factory;
    @Mock private RdsCloudProviderService rdsService;
    @Mock private CloudWatchClient cloudWatch;

    private RdsCloudMetricsProviderService service;

    @BeforeEach
    void setUp() {
        lenient().when(factory.isConfigured()).thenReturn(true);
        lenient().when(rdsService.isEnabled()).thenReturn(true);
        lenient().when(factory.cloudWatch(anyString(), eq(REGION))).thenReturn(cloudWatch);
        service = new RdsCloudMetricsProviderService(factory, rdsService);
        ReflectionTestUtils.setField(service, "defaultRegion", REGION);
    }

    /** Answers every query with one datapoint at T0 whose value is chosen by the metric name. */
    private List<MetricDataQuery> answer(Map<String, Double> byMetric) {
        List<MetricDataQuery> seen = new ArrayList<>();
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenAnswer(inv -> {
            GetMetricDataRequest request = inv.getArgument(0);
            seen.addAll(request.metricDataQueries());
            List<MetricDataResult> results = request.metricDataQueries().stream()
                    .filter(q -> byMetric.containsKey(q.metricStat().metric().metricName()))
                    .map(q -> MetricDataResult.builder().id(q.id()).timestamps(T0)
                            .values(byMetric.get(q.metricStat().metric().metricName())).build())
                    .toList();
            return GetMetricDataResponse.builder().metricDataResults(results).build();
        });
        return seen;
    }

    @Test
    void queriesUseTheRdsNamespaceAndTheInstanceOrClusterDimension() {
        List<MetricDataQuery> seen = answer(Map.of());

        service.fetchMetricSeries(List.of("db:orders", "cluster:aurora-qa", "i-not-rds"), CloudTarget.of(REGION),
                T0.minusSeconds(3600), T0, 300);

        assertEquals(12, seen.size());
        assertTrue(seen.stream().allMatch(q -> "AWS/RDS".equals(q.metricStat().metric().namespace())));
        MetricDataQuery connections = seen.stream()
                .filter(q -> q.metricStat().metric().metricName().equals("DatabaseConnections")
                        && q.metricStat().metric().dimensions().get(0).value().equals("orders"))
                .findFirst().orElseThrow();
        assertEquals("DBInstanceIdentifier", connections.metricStat().metric().dimensions().get(0).name());
        assertEquals("Maximum", connections.metricStat().stat());
        assertEquals(300, connections.metricStat().period());
        assertTrue(seen.stream().anyMatch(q -> q.metricStat().metric().dimensions().get(0).name().equals("DBClusterIdentifier")
                && q.metricStat().metric().dimensions().get(0).value().equals("aurora-qa")
                && q.metricStat().metric().metricName().equals("CPUUtilization")
                && q.metricStat().stat().equals("Average")));
    }

    @Test
    void connectionsCpuAndThroughputsAreMappedPerTimestamp() {
        answer(Map.of("CPUUtilization", 12.3456, "DatabaseConnections", 7.0, "ReadThroughput", 100.0,
                "WriteThroughput", 2.5, "NetworkReceiveThroughput", 10.0, "NetworkTransmitThroughput", 20.0));

        Map<String, List<CloudMetricsProviderService.VmMetricData>> series = service.fetchMetricSeries(
                List.of("db:orders"), CloudTarget.of(REGION), T0.minusSeconds(600), T0.plusSeconds(1), 300);

        List<CloudMetricsProviderService.VmMetricData> points = series.get("db:orders");
        assertEquals(1, points.size());
        CloudMetricsProviderService.VmMetricData d = points.get(0);
        assertEquals("db:orders", d.getProviderVmId());
        assertEquals(new BigDecimal("12.346"), d.getCpuUtilization());
        assertEquals(new BigDecimal("7.000"), d.getDbConnections());
        assertEquals(30_000L, d.getDiskReadBytes());
        assertEquals(750L, d.getDiskWriteBytes());
        assertEquals(3_000L, d.getNetworkInBytes());
        assertEquals(6_000L, d.getNetworkOutBytes());
        assertNull(d.getMemoryUtilization());
        assertEquals(T0, d.getSampleTime().toInstant());
    }

    @Test
    void switchedOffNothingIsQueried() {
        when(rdsService.isEnabled()).thenReturn(false);

        assertFalse(service.isAvailable());
        assertTrue(service.fetchMetricSeries(List.of("db:orders"), CloudTarget.of(REGION), T0.minusSeconds(600), T0, 300).isEmpty());
        verifyNoInteractions(cloudWatch);
        assertEquals(CloudProvider.AWS_RDS, service.getProvider());
    }

    @Test
    void aFailingCallIsLoggedAndYieldsNothing() {
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenThrow(new RuntimeException("throttled"));

        assertTrue(service.fetchLatestMetrics(List.of("db:orders"), CloudTarget.of(REGION), T0.minusSeconds(600), T0, 300).isEmpty());
        ArgumentCaptor<GetMetricDataRequest> request = ArgumentCaptor.forClass(GetMetricDataRequest.class);
        verify(cloudWatch).getMetricData(request.capture());
        assertEquals(6, request.getValue().metricDataQueries().size());
    }
}
