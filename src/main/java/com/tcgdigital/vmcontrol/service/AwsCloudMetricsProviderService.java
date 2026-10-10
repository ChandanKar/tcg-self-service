package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AwsCloudMetricsProviderService implements CloudMetricsProviderService {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudMetricsProviderService.class);
    private static final String NAMESPACE = "AWS/EC2";
    /**
     * Memory isn't part of standard EC2 monitoring — it only exists if the CloudWatch Agent is
     * installed and configured on the instance, publishing "mem_used_percent" to this namespace
     * with an InstanceId dimension (the unified agent's documented defaults). If the agent isn't
     * present, GetMetricData simply returns no data points for this query and memoryUtilization
     * stays null, same graceful-degradation behavior as every other missing-data case here.
     */
    private static final String CWAGENT_NAMESPACE = "CWAgent";

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    private final Map<String, CloudWatchClient> clientCache = new ConcurrentHashMap<>();

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    public boolean isAvailable() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    /** The newest datapoint of each VM's series (kept for callers that want only the latest). */
    @Override
    public Map<String, VmMetricData> fetchLatestMetrics(List<String> providerVmIds, String region,
                                                        Instant start, Instant end, int periodSeconds) {
        Map<String, VmMetricData> latest = new HashMap<>();
        fetchMetricSeries(providerVmIds, region, start, end, periodSeconds).forEach((id, series) -> {
            if (!series.isEmpty()) {
                latest.put(id, series.get(series.size() - 1));
            }
        });
        return latest;
    }

    /**
     * Every datapoint per VM in the window (E12-T03): values are aligned by their own timestamp,
     * every page (NextToken) is read, and a failing chunk of 80 instances is logged and skipped
     * without losing the other chunks.
     */
    @Override
    public Map<String, List<VmMetricData>> fetchMetricSeries(List<String> providerVmIds, String region,
                                                             Instant start, Instant end, int periodSeconds) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) {
            return Collections.emptyMap();
        }
        CloudWatchClient cloudWatch = getCloudWatchClient(region);
        Map<String, java.util.TreeMap<Instant, VmMetricData>> byInstance = new HashMap<>();
        int queryCounter = 0;
        for (int offset = 0; offset < providerVmIds.size(); offset += 80) {
            List<String> chunk = providerVmIds.subList(offset, Math.min(offset + 80, providerVmIds.size()));
            Map<String, QueryTarget> targets = new HashMap<>();
            List<MetricDataQuery> queries = new ArrayList<>();
            for (String instanceId : chunk) {
                for (MetricSpec spec : MetricSpec.values()) {
                    String queryId = "m" + queryCounter++;
                    targets.put(queryId, new QueryTarget(instanceId, spec));
                    queries.add(MetricDataQuery.builder()
                            .id(queryId)
                            .metricStat(MetricStat.builder()
                                    .metric(Metric.builder()
                                            .namespace(spec.namespace)
                                            .metricName(spec.metricName)
                                            .dimensions(Dimension.builder()
                                                    .name("InstanceId")
                                                    .value(instanceId)
                                                    .build())
                                            .build())
                                    .period(periodSeconds)
                                    .stat(spec.stat)
                                    .build())
                            .returnData(true)
                            .build());
                }
            }
            try {
                String token = null;
                do {
                    GetMetricDataResponse response = cloudWatch.getMetricData(GetMetricDataRequest.builder()
                            .startTime(start)
                            .endTime(end)
                            .scanBy(ScanBy.TIMESTAMP_ASCENDING)
                            .metricDataQueries(queries)
                            .nextToken(token)
                            .build());
                    for (MetricDataResult metricResult : response.metricDataResults()) {
                        QueryTarget target = targets.get(metricResult.id());
                        if (target == null) continue;
                        List<Instant> times = metricResult.timestamps();
                        List<Double> values = metricResult.values();
                        for (int i = 0; i < Math.min(times.size(), values.size()); i++) {
                            Instant at = times.get(i);
                            VmMetricData data = byInstance
                                    .computeIfAbsent(target.instanceId, id -> new java.util.TreeMap<>())
                                    .computeIfAbsent(at, t -> {
                                        VmMetricData created = new VmMetricData();
                                        created.setProviderVmId(target.instanceId);
                                        created.setPeriodSeconds(periodSeconds);
                                        created.setSampleTime(Timestamp.from(t));
                                        return created;
                                    });
                            applyValue(data, target.spec, values.get(i));
                        }
                    }
                    token = response.nextToken();
                } while (token != null && !token.isEmpty());
            } catch (Exception e) {
                log.error("Failed to fetch AWS CloudWatch metrics for chunk {} in {}: {}", chunk, region, e.getMessage());
            }
        }
        Map<String, List<VmMetricData>> result = new HashMap<>();
        byInstance.forEach((id, series) -> result.put(id, new ArrayList<>(series.values())));
        return result;
    }

    private void applyValue(VmMetricData data, MetricSpec spec, Double value) {
        if (value == null) return;
        switch (spec) {
            case CPU -> data.setCpuUtilization(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP));
            case MEMORY -> data.setMemoryUtilization(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP));
            case NETWORK_IN -> data.setNetworkInBytes(Math.round(value));
            case NETWORK_OUT -> data.setNetworkOutBytes(Math.round(value));
            case DISK_READ -> data.setDiskReadBytes(Math.round(value));
            case DISK_WRITE -> data.setDiskWriteBytes(Math.round(value));
        }
    }

    private CloudWatchClient getCloudWatchClient(String region) {
        String effectiveRegion = region != null && !region.isBlank() ? region : defaultRegion;
        return clientCache.computeIfAbsent(effectiveRegion, r -> CloudWatchClient.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build());
    }

    private enum MetricSpec {
        CPU(NAMESPACE, "CPUUtilization", "Average"),
        MEMORY(CWAGENT_NAMESPACE, "mem_used_percent", "Average"),
        NETWORK_IN(NAMESPACE, "NetworkIn", "Sum"),
        NETWORK_OUT(NAMESPACE, "NetworkOut", "Sum"),
        DISK_READ(NAMESPACE, "DiskReadBytes", "Sum"),
        DISK_WRITE(NAMESPACE, "DiskWriteBytes", "Sum");

        private final String namespace;
        private final String metricName;
        private final String stat;

        MetricSpec(String namespace, String metricName, String stat) {
            this.namespace = namespace;
            this.metricName = metricName;
            this.stat = stat;
        }
    }

    private record QueryTarget(String instanceId, MetricSpec spec) {
    }
}
