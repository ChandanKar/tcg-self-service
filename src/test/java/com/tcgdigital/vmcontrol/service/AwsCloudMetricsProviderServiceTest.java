package com.tcgdigital.vmcontrol.service;

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
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CloudWatch datapoints are kept per timestamp, every page is read, and a failing chunk does not
 * lose the others (E12-T03). Query ids are m0.. in MetricSpec order per instance: CPU, MEMORY,
 * NETWORK_IN, NETWORK_OUT, DISK_READ, DISK_WRITE.
 */
@ExtendWith(MockitoExtension.class)
class AwsCloudMetricsProviderServiceTest {

    private static final String REGION = "us-east-1";
    private static final Instant T1000 = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant T0955 = Instant.parse("2026-10-11T09:55:00Z");

    @Mock private CloudWatchClient cloudWatch;

    private AwsCloudMetricsProviderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AwsCloudMetricsProviderService();
        ReflectionTestUtils.setField(service, "accessKey", "k");
        ReflectionTestUtils.setField(service, "secretKey", "s");
        ((Map<String, CloudWatchClient>) ReflectionTestUtils.getField(service, "clientCache")).put(REGION, cloudWatch);
    }

    private static MetricDataResult result(String id, Instant at, double value) {
        return MetricDataResult.builder().id(id).timestamps(at).values(value).build();
    }

    private Map<String, List<CloudMetricsProviderService.VmMetricData>> fetch(List<String> ids) {
        return service.fetchMetricSeries(ids, REGION, T0955.minusSeconds(600), T1000.plusSeconds(60), 300);
    }

    @Test
    void valuesFromDifferentTimestampsStayInDifferentSamples() {
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenReturn(GetMetricDataResponse.builder()
                .metricDataResults(result("m0", T1000, 12.0), result("m2", T0955, 1000.0)).build());

        List<CloudMetricsProviderService.VmMetricData> series = fetch(List.of("i-1")).get("i-1");

        assertThat(series).hasSize(2);
        assertThat(series.get(0).getSampleTime().toInstant()).isEqualTo(T0955);
        assertThat(series.get(0).getNetworkInBytes()).isEqualTo(1000L);
        assertThat(series.get(0).getCpuUtilization()).isNull();
        assertThat(series.get(1).getSampleTime().toInstant()).isEqualTo(T1000);
        assertThat(series.get(1).getCpuUtilization()).isEqualByComparingTo("12");
        assertThat(series.get(1).getNetworkInBytes()).isNull();
    }

    @Test
    void everyPageIsRead() {
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class)))
                .thenReturn(GetMetricDataResponse.builder().metricDataResults(result("m0", T0955, 5.0)).nextToken("page-2").build())
                .thenReturn(GetMetricDataResponse.builder().metricDataResults(result("m0", T1000, 7.0)).build());

        List<CloudMetricsProviderService.VmMetricData> series = fetch(List.of("i-1")).get("i-1");

        ArgumentCaptor<GetMetricDataRequest> requests = ArgumentCaptor.forClass(GetMetricDataRequest.class);
        verify(cloudWatch, times(2)).getMetricData(requests.capture());
        assertThat(requests.getAllValues().get(1).nextToken()).isEqualTo("page-2");
        assertThat(series).hasSize(2);
    }

    @Test
    void aFailingChunkDoesNotLoseTheOthers() {
        List<String> ids = IntStream.range(0, 161).mapToObj(i -> "i-" + i).toList(); // three chunks of 80
        when(cloudWatch.getMetricData(any(GetMetricDataRequest.class)))
                .thenReturn(GetMetricDataResponse.builder().metricDataResults(result("m0", T1000, 1.0)).build())
                .thenThrow(new RuntimeException("throttled"))
                .thenReturn(GetMetricDataResponse.builder().metricDataResults(result("m960", T1000, 3.0)).build());

        Map<String, List<CloudMetricsProviderService.VmMetricData>> series = fetch(ids);

        assertThat(series).containsKeys("i-0", "i-160");
        assertThat(series.get("i-160").get(0).getCpuUtilization()).isEqualByComparingTo("3");
    }
}
