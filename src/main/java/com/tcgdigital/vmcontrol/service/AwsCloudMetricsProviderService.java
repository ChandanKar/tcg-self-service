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

    @Override
    public Map<String, VmMetricData> fetchLatestMetrics(List<String> providerVmIds, String region,
                                                        Instant start, Instant end, int periodSeconds) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, VmMetricData> result = new HashMap<>();
        try {
            CloudWatchClient cloudWatch = getCloudWatchClient(region);
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
                                                .namespace(NAMESPACE)
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

                GetMetricDataResponse response = cloudWatch.getMetricData(GetMetricDataRequest.builder()
                        .startTime(start)
                        .endTime(end)
                        .scanBy(ScanBy.TIMESTAMP_DESCENDING)
                        .metricDataQueries(queries)
                        .build());

                for (MetricDataResult metricResult : response.metricDataResults()) {
                    if (metricResult.values().isEmpty()) continue;
                    QueryTarget target = targets.get(metricResult.id());
                    if (target == null) continue;

                    VmMetricData data = result.computeIfAbsent(target.instanceId, id -> {
                        VmMetricData created = new VmMetricData();
                        created.setProviderVmId(id);
                        created.setPeriodSeconds(periodSeconds);
                        return created;
                    });

                    Double value = metricResult.values().get(0);
                    Instant sampleInstant = metricResult.timestamps().isEmpty() ? end : metricResult.timestamps().get(0);
                    data.setSampleTime(Timestamp.from(sampleInstant));
                    applyValue(data, target.spec, value);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch AWS CloudWatch metrics for {} VM(s) in {}: {}", providerVmIds.size(), region, e.getMessage());
        }
        return result;
    }

    private void applyValue(VmMetricData data, MetricSpec spec, Double value) {
        if (value == null) return;
        switch (spec) {
            case CPU -> data.setCpuUtilization(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP));
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
        CPU("CPUUtilization", "Average"),
        NETWORK_IN("NetworkIn", "Sum"),
        NETWORK_OUT("NetworkOut", "Sum"),
        DISK_READ("DiskReadBytes", "Sum"),
        DISK_WRITE("DiskWriteBytes", "Sum");

        private final String metricName;
        private final String stat;

        MetricSpec(String metricName, String stat) {
            this.metricName = metricName;
            this.stat = stat;
        }
    }

    private record QueryTarget(String instanceId, MetricSpec spec) {
    }
}
