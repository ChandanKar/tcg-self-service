package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmRepository;
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
import java.util.function.Function;

/**
 * CloudWatch metrics for EKS node groups (E26-T01). A node group's Vm (providerVmId
 * "cluster/nodegroup") carries its Auto Scaling group names in metadata (asgNames, written by
 * EksSyncService); AWS/EC2 metrics are read per AutoScalingGroupName and combined: CPU is the
 * average over the groups' ASGs (each already the per-node average), byte counts are summed. With
 * Container Insights on, the cluster's node_memory_utilization is the memory figure. Off unless
 * eks.metrics.enabled; a node group without asgNames is skipped until the next EKS sync.
 */
@Service
public class AwsEksMetricsProviderService implements CloudMetricsProviderService {

    private static final Logger log = LoggerFactory.getLogger(AwsEksMetricsProviderService.class);
    private static final String EC2_NAMESPACE = "AWS/EC2";
    private static final String CONTAINER_INSIGHTS = "ContainerInsights";
    private static final int MAX_QUERIES = 500;

    private final VmRepository vmRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, CloudWatchClient> clientCache = new ConcurrentHashMap<>();
    /** Builds the client for a region; replaced in tests. */
    Function<String, CloudWatchClient> clientFactory = this::buildClient;

    @Value("${aws.access-key:}")
    private String accessKey = "";

    @Value("${aws.secret-key:}")
    private String secretKey = "";

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion = "ap-south-1";

    @Value("${eks.metrics.enabled:false}")
    private boolean enabled;

    @Value("${eks.metrics.container-insights.enabled:false}")
    private boolean containerInsights;

    public AwsEksMetricsProviderService(VmRepository vmRepository, ObjectMapper objectMapper) {
        this.vmRepository = vmRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS_EKS;
    }

    @Override
    public boolean isAvailable() {
        return enabled && accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

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

    private enum Spec {
        CPU("CPUUtilization", "Average"),
        NETWORK_IN("NetworkIn", "Sum"),
        NETWORK_OUT("NetworkOut", "Sum"),
        DISK_READ("DiskReadBytes", "Sum"),
        DISK_WRITE("DiskWriteBytes", "Sum");

        final String metricName;
        final String stat;

        Spec(String metricName, String stat) {
            this.metricName = metricName;
            this.stat = stat;
        }
    }

    /** One query's target: a node group and either an ASG metric or the cluster's memory. */
    private record Target(String nodeGroupId, Spec spec, boolean memory) {}

    /** Values of one node group at one timestamp, before combining ASGs. */
    private static final class Point {
        final List<Double> cpu = new ArrayList<>();
        final Map<Spec, Double> sums = new EnumMap<>(Spec.class);
        Double memory;
    }

    @Override
    public Map<String, List<VmMetricData>> fetchMetricSeries(List<String> providerVmIds, String region,
                                                             Instant start, Instant end, int periodSeconds) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // Node group -> its ASGs; cluster -> node groups (for the cluster-wide memory figure).
        Map<String, List<String>> asgsByNodeGroup = new LinkedHashMap<>();
        Map<String, List<String>> nodeGroupsByCluster = new LinkedHashMap<>();
        for (String id : providerVmIds) {
            List<String> asgs = asgNames(id);
            if (asgs.isEmpty()) {
                log.debug("EKS node group {} has no Auto Scaling group names yet; skipped until the next EKS sync", id);
                continue;
            }
            asgsByNodeGroup.put(id, asgs);
            int slash = id.indexOf('/');
            if (slash > 0) {
                nodeGroupsByCluster.computeIfAbsent(id.substring(0, slash), c -> new ArrayList<>()).add(id);
            }
        }
        if (asgsByNodeGroup.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Target> targets = new HashMap<>();
        List<MetricDataQuery> queries = new ArrayList<>();
        int counter = 0;
        for (Map.Entry<String, List<String>> e : asgsByNodeGroup.entrySet()) {
            for (String asg : e.getValue()) {
                for (Spec spec : Spec.values()) {
                    String queryId = "q" + counter++;
                    targets.put(queryId, new Target(e.getKey(), spec, false));
                    queries.add(query(queryId, EC2_NAMESPACE, spec.metricName, spec.stat, "AutoScalingGroupName", asg, periodSeconds));
                }
            }
        }
        if (containerInsights) {
            for (Map.Entry<String, List<String>> e : nodeGroupsByCluster.entrySet()) {
                String queryId = "q" + counter++;
                // One figure per cluster; every node group of the cluster gets it.
                targets.put(queryId, new Target(e.getKey(), null, true));
                queries.add(query(queryId, CONTAINER_INSIGHTS, "node_memory_utilization", "Average", "ClusterName", e.getKey(), periodSeconds));
            }
        }

        Map<String, TreeMap<Instant, Point>> points = new HashMap<>();
        Map<String, TreeMap<Instant, Double>> memoryByCluster = new HashMap<>();
        CloudWatchClient cloudWatch = clientCache.computeIfAbsent(effectiveRegion(region), clientFactory);
        for (int offset = 0; offset < queries.size(); offset += MAX_QUERIES) {
            List<MetricDataQuery> chunk = queries.subList(offset, Math.min(offset + MAX_QUERIES, queries.size()));
            try {
                String token = null;
                do {
                    GetMetricDataResponse response = cloudWatch.getMetricData(GetMetricDataRequest.builder()
                            .startTime(start).endTime(end).scanBy(ScanBy.TIMESTAMP_ASCENDING)
                            .metricDataQueries(chunk).nextToken(token).build());
                    for (MetricDataResult result : response.metricDataResults()) {
                        Target target = targets.get(result.id());
                        if (target == null) {
                            continue;
                        }
                        List<Instant> times = result.timestamps();
                        List<Double> values = result.values();
                        for (int i = 0; i < Math.min(times.size(), values.size()); i++) {
                            Double value = values.get(i);
                            if (value == null) {
                                continue;
                            }
                            if (target.memory()) {
                                memoryByCluster.computeIfAbsent(target.nodeGroupId(), c -> new TreeMap<>()).put(times.get(i), value);
                                continue;
                            }
                            Point point = points.computeIfAbsent(target.nodeGroupId(), id -> new TreeMap<>())
                                    .computeIfAbsent(times.get(i), t -> new Point());
                            if (target.spec() == Spec.CPU) {
                                point.cpu.add(value);
                            } else {
                                point.sums.merge(target.spec(), value, Double::sum);
                            }
                        }
                    }
                    token = response.nextToken();
                } while (token != null && !token.isEmpty());
            } catch (Exception e) {
                log.error("Failed to fetch EKS node-group metrics in {}: {}", region, e.getMessage());
            }
        }

        Map<String, List<VmMetricData>> result = new HashMap<>();
        points.forEach((nodeGroupId, series) -> {
            String cluster = nodeGroupId.contains("/") ? nodeGroupId.substring(0, nodeGroupId.indexOf('/')) : null;
            TreeMap<Instant, Double> memory = cluster == null ? null : memoryByCluster.get(cluster);
            List<VmMetricData> samples = new ArrayList<>();
            series.forEach((at, p) -> {
                VmMetricData data = new VmMetricData();
                data.setProviderVmId(nodeGroupId);
                data.setSampleTime(Timestamp.from(at));
                data.setPeriodSeconds(periodSeconds);
                if (!p.cpu.isEmpty()) {
                    double avg = p.cpu.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    data.setCpuUtilization(BigDecimal.valueOf(avg).setScale(3, RoundingMode.HALF_UP));
                }
                data.setNetworkInBytes(round(p.sums.get(Spec.NETWORK_IN)));
                data.setNetworkOutBytes(round(p.sums.get(Spec.NETWORK_OUT)));
                data.setDiskReadBytes(round(p.sums.get(Spec.DISK_READ)));
                data.setDiskWriteBytes(round(p.sums.get(Spec.DISK_WRITE)));
                if (memory != null && memory.containsKey(at)) {
                    data.setMemoryUtilization(BigDecimal.valueOf(memory.get(at)).setScale(3, RoundingMode.HALF_UP));
                }
                samples.add(data);
            });
            result.put(nodeGroupId, samples);
        });
        return result;
    }

    /** The node group's Auto Scaling group names from its Vm metadata (empty when unknown). */
    List<String> asgNames(String providerVmId) {
        Optional<Vm> vm = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, providerVmId);
        if (vm.isEmpty() || vm.get().getMetadata() == null || vm.get().getMetadata().isBlank()) {
            return List.of();
        }
        try {
            JsonNode names = objectMapper.readTree(vm.get().getMetadata()).path("asgNames");
            List<String> out = new ArrayList<>();
            names.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText());
                }
            });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static MetricDataQuery query(String id, String namespace, String metric, String stat, String dimension, String value,
                                         int periodSeconds) {
        return MetricDataQuery.builder().id(id)
                .metricStat(MetricStat.builder()
                        .metric(Metric.builder().namespace(namespace).metricName(metric)
                                .dimensions(Dimension.builder().name(dimension).value(value).build()).build())
                        .period(periodSeconds).stat(stat).build())
                .returnData(true).build();
    }

    private static Long round(Double value) {
        return value == null ? null : Math.round(value);
    }

    private String effectiveRegion(String region) {
        return region != null && !region.isBlank() ? region : defaultRegion;
    }

    private CloudWatchClient buildClient(String region) {
        return CloudWatchClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build();
    }
}
