package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CloudWatch metrics of AWS_RDS VMs (E33-T03), namespace AWS/RDS with the DBInstanceIdentifier
 * (or DBClusterIdentifier) dimension: CPUUtilization (Average), DatabaseConnections (Maximum), and
 * the network and storage throughputs (bytes a second, Average) turned into bytes over the period
 * so they read like the EC2 byte counters idle detection compares. Memory stays empty: RDS reports
 * free memory, not a percentage. Nothing is read while RDS is off.
 */
@Service
public class RdsCloudMetricsProviderService implements CloudMetricsProviderService {

    private static final Logger log = LoggerFactory.getLogger(RdsCloudMetricsProviderService.class);
    static final String NAMESPACE = "AWS/RDS";
    private static final int CHUNK = 80;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    private final AwsClientFactory awsClientFactory;
    private final RdsCloudProviderService rdsService;

    public RdsCloudMetricsProviderService(AwsClientFactory awsClientFactory, RdsCloudProviderService rdsService) {
        this.awsClientFactory = awsClientFactory;
        this.rdsService = rdsService;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS_RDS;
    }

    @Override
    public boolean isAvailable() {
        return rdsService.isEnabled() && awsClientFactory.isConfigured();
    }

    @Override
    public Map<String, VmMetricData> fetchLatestMetrics(List<String> providerVmIds, CloudTarget target,
                                                        Instant start, Instant end, int periodSeconds) {
        Map<String, VmMetricData> latest = new HashMap<>();
        fetchMetricSeries(providerVmIds, target, start, end, periodSeconds).forEach((id, series) -> {
            if (!series.isEmpty()) latest.put(id, series.get(series.size() - 1));
        });
        return latest;
    }

    @Override
    public Map<String, List<VmMetricData>> fetchMetricSeries(List<String> providerVmIds, CloudTarget target,
                                                             Instant start, Instant end, int periodSeconds) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<RdsCloudProviderService.RdsId> ids = providerVmIds.stream().map(RdsCloudProviderService::parse)
                .filter(id -> id != null).toList();
        CloudWatchClient cloudWatch = client(target);
        Map<String, TreeMap<Instant, VmMetricData>> byVm = new HashMap<>();
        int counter = 0;
        for (int offset = 0; offset < ids.size(); offset += CHUNK) {
            List<RdsCloudProviderService.RdsId> chunk = ids.subList(offset, Math.min(offset + CHUNK, ids.size()));
            Map<String, QueryTarget> targets = new HashMap<>();
            List<MetricDataQuery> queries = new ArrayList<>();
            for (RdsCloudProviderService.RdsId id : chunk) {
                for (MetricSpec spec : MetricSpec.values()) {
                    String queryId = "r" + counter++;
                    targets.put(queryId, new QueryTarget(id.providerVmId(), spec));
                    queries.add(query(queryId, id, spec, periodSeconds));
                }
            }
            try {
                String token = null;
                do {
                    GetMetricDataResponse response = cloudWatch.getMetricData(GetMetricDataRequest.builder()
                            .startTime(start).endTime(end).scanBy(ScanBy.TIMESTAMP_ASCENDING)
                            .metricDataQueries(queries).nextToken(token).build());
                    for (MetricDataResult r : response.metricDataResults()) {
                        QueryTarget t = targets.get(r.id());
                        if (t == null) continue;
                        for (int i = 0; i < Math.min(r.timestamps().size(), r.values().size()); i++) {
                            Instant at = r.timestamps().get(i);
                            VmMetricData data = byVm.computeIfAbsent(t.providerVmId(), k -> new TreeMap<>())
                                    .computeIfAbsent(at, k -> {
                                        VmMetricData created = new VmMetricData();
                                        created.setProviderVmId(t.providerVmId());
                                        created.setPeriodSeconds(periodSeconds);
                                        created.setSampleTime(Timestamp.from(k));
                                        return created;
                                    });
                            apply(data, t.spec(), r.values().get(i), periodSeconds);
                        }
                    }
                    token = response.nextToken();
                } while (token != null && !token.isEmpty());
            } catch (Exception e) {
                log.error("Failed to fetch RDS CloudWatch metrics for {} database(s) in {}: {}", chunk.size(), target, e.getMessage());
            }
        }
        Map<String, List<VmMetricData>> result = new HashMap<>();
        byVm.forEach((id, series) -> result.put(id, new ArrayList<>(series.values())));
        return result;
    }

    static MetricDataQuery query(String queryId, RdsCloudProviderService.RdsId id, MetricSpec spec, int periodSeconds) {
        return MetricDataQuery.builder()
                .id(queryId)
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder()
                                .namespace(NAMESPACE)
                                .metricName(spec.metricName)
                                .dimensions(Dimension.builder()
                                        .name(id.cluster() ? "DBClusterIdentifier" : "DBInstanceIdentifier")
                                        .value(id.identifier())
                                        .build())
                                .build())
                        .period(periodSeconds)
                        .stat(spec.stat)
                        .build())
                .returnData(true)
                .build();
    }

    private static void apply(VmMetricData data, MetricSpec spec, Double value, int periodSeconds) {
        if (value == null) return;
        switch (spec) {
            case CPU -> data.setCpuUtilization(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP));
            case CONNECTIONS -> data.setDbConnections(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP));
            case NETWORK_IN -> data.setNetworkInBytes(Math.round(value * periodSeconds));
            case NETWORK_OUT -> data.setNetworkOutBytes(Math.round(value * periodSeconds));
            case DISK_READ -> data.setDiskReadBytes(Math.round(value * periodSeconds));
            case DISK_WRITE -> data.setDiskWriteBytes(Math.round(value * periodSeconds));
        }
    }

    private CloudWatchClient client(CloudTarget target) {
        String region = target.region() != null && !target.region().isBlank() ? target.region() : defaultRegion;
        return awsClientFactory.cloudWatch(target.accountRef(), region);
    }

    enum MetricSpec {
        CPU("CPUUtilization", "Average"),
        CONNECTIONS("DatabaseConnections", "Maximum"),
        NETWORK_IN("NetworkReceiveThroughput", "Average"),
        NETWORK_OUT("NetworkTransmitThroughput", "Average"),
        DISK_READ("ReadThroughput", "Average"),
        DISK_WRITE("WriteThroughput", "Average");

        final String metricName;
        final String stat;

        MetricSpec(String metricName, String stat) {
            this.metricName = metricName;
            this.stat = stat;
        }
    }

    private record QueryTarget(String providerVmId, MetricSpec spec) {
    }
}
