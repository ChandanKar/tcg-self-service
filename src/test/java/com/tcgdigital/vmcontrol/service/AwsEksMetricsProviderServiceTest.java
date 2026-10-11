package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EKS node-group metrics by Auto Scaling group (E26-T01): queries, multi-ASG aggregation, the off switch. */
class AwsEksMetricsProviderServiceTest {

    private static final String NG = "prod/workers";
    private static final Instant T = Instant.parse("2026-10-12T10:00:00Z");

    private VmRepository vms;
    private CloudWatchClient cloudWatch;
    private AwsEksMetricsProviderService service;
    private final AtomicInteger clientsBuilt = new AtomicInteger();

    @BeforeEach
    void setUp() {
        vms = mock(VmRepository.class);
        cloudWatch = mock(CloudWatchClient.class);
        service = new AwsEksMetricsProviderService(vms, new ObjectMapper());
        ReflectionTestUtils.setField(service, "accessKey", "AKIA-test");
        ReflectionTestUtils.setField(service, "secretKey", "secret");
        ReflectionTestUtils.setField(service, "enabled", true);
        service.clientFactory = region -> {
            clientsBuilt.incrementAndGet();
            return cloudWatch;
        };
    }

    private void nodeGroup(String metadata) {
        Vm vm = new Vm();
        vm.setProviderVmId(NG);
        vm.setMetadata(metadata);
        when(vms.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, NG)).thenReturn(Optional.of(vm));
    }

    /** Answers each query by its metric and ASG: CPU 10 / 30, NetworkIn 100 / 200, others 1. */
    private void answer() {
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenAnswer(inv -> {
            GetMetricDataRequest request = inv.getArgument(0);
            List<MetricDataResult> results = new ArrayList<>();
            for (MetricDataQuery q : request.metricDataQueries()) {
                Metric m = q.metricStat().metric();
                String dimension = m.dimensions().get(0).value();
                double value = switch (m.metricName()) {
                    case "CPUUtilization" -> dimension.equals("asg-a") ? 10 : 30;
                    case "NetworkIn" -> dimension.equals("asg-a") ? 100 : 200;
                    case "node_memory_utilization" -> 55.5;
                    default -> 1;
                };
                results.add(MetricDataResult.builder().id(q.id()).timestamps(T).values(value).build());
            }
            return GetMetricDataResponse.builder().metricDataResults(results).build();
        });
    }

    @Test
    void cpuIsAveragedAndBytesSummedAcrossTheGroupsAsgs() {
        nodeGroup("{\"minSize\":1,\"desiredSize\":2,\"asgNames\":[\"asg-a\",\"asg-b\"]}");
        answer();

        Map<String, List<CloudMetricsProviderService.VmMetricData>> series =
                service.fetchMetricSeries(List.of(NG), "ap-south-1", T.minusSeconds(300), T.plusSeconds(300), 300);

        assertThat(series).containsKey(NG);
        CloudMetricsProviderService.VmMetricData sample = series.get(NG).get(0);
        assertThat(sample.getCpuUtilization()).isEqualByComparingTo("20");
        assertThat(sample.getNetworkInBytes()).isEqualTo(300L);
        assertThat(sample.getDiskWriteBytes()).isEqualTo(2L);
        assertThat(sample.getMemoryUtilization()).isNull();

        ArgumentCaptor<GetMetricDataRequest> request = ArgumentCaptor.forClass(GetMetricDataRequest.class);
        verify(cloudWatch).getMetricData(request.capture());
        assertThat(request.getValue().metricDataQueries()).hasSize(10)
                .allSatisfy(q -> assertThat(q.metricStat().metric().dimensions().get(0).name()).isEqualTo("AutoScalingGroupName"));
    }

    @Test
    void containerInsightsAddsTheClusterMemory() {
        ReflectionTestUtils.setField(service, "containerInsights", true);
        nodeGroup("{\"asgNames\":[\"asg-a\"]}");
        answer();
        CloudMetricsProviderService.VmMetricData sample = service.fetchLatestMetrics(List.of(NG), "ap-south-1",
                T.minusSeconds(300), T.plusSeconds(300), 300).get(NG);
        assertThat(sample.getMemoryUtilization()).isEqualByComparingTo("55.5");
        assertThat(sample.getCpuUtilization()).isEqualByComparingTo("10");
    }

    @Test
    void offOrWithoutAsgNamesNothingIsCalled() {
        nodeGroup("{\"minSize\":1,\"desiredSize\":2}");
        assertThat(service.fetchMetricSeries(List.of(NG), "ap-south-1", T.minusSeconds(300), T, 300)).isEmpty();

        nodeGroup("{\"asgNames\":[\"asg-a\"]}");
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.fetchMetricSeries(List.of(NG), "ap-south-1", T.minusSeconds(300), T, 300)).isEmpty();

        verifyNoInteractions(cloudWatch);
        assertThat(clientsBuilt.get()).isZero();
        assertThat(service.getProvider()).isEqualTo(CloudProvider.AWS_EKS);
    }
}
