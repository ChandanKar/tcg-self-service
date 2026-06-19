package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS EKS implementation of CloudProviderService.
 *
 * providerVmId convention: "{clusterName}/{nodeGroupName}"
 * region: AWS region string (e.g. "ap-south-1")
 *
 * Start = restore saved minSize; set desiredSize = minSize
 * Stop  = save current minSize/desiredSize to Vm.metadata; scale both to 0
 */
@Service
public class EksCloudProviderService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(EksCloudProviderService.class);

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    @Value("${eks.nodegroup.default-min-size:1}")
    private int defaultMinSize;

    @Value("${eks.nodegroup.poll-interval-ms:15000}")
    private long pollIntervalMs;

    @Value("${eks.nodegroup.operation-timeout-ms:900000}")
    private long operationTimeoutMs;

    private final Map<String, EksClient> clientCache = new ConcurrentHashMap<>();

    private final VmRepository vmRepository;
    private final ObjectMapper objectMapper;

    public EksCloudProviderService(VmRepository vmRepository, ObjectMapper objectMapper) {
        this.vmRepository = vmRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS_EKS;
    }

    @Override
    public boolean isAvailable() {
        return accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, String region) {
        return CompletableFuture.supplyAsync(() -> {
            String[] parts = parseProviderVmId(providerVmId);
            if (parts == null) {
                return VmOperationResult.failure("Invalid providerVmId format (expected clusterName/nodeGroupName): " + providerVmId);
            }
            String clusterName = parts[0];
            String nodeGroupName = parts[1];

            // Restore saved minSize; desiredSize = minSize (start exactly that many nodes)
            int minSize = readSavedMinSize(providerVmId);

            EksClient eks = getEksClient(region);
            try {
                UpdateNodegroupConfigRequest request = UpdateNodegroupConfigRequest.builder()
                        .clusterName(clusterName)
                        .nodegroupName(nodeGroupName)
                        .scalingConfig(NodegroupScalingConfig.builder()
                                .minSize(minSize)
                                .desiredSize(minSize)
                                .build())
                        .build();

                UpdateNodegroupConfigResponse response = eks.updateNodegroupConfig(request);
                String updateId = response.update() != null ? response.update().id() : "unknown";

                log.info("EKS node group {}/{} scale-up to {} requested (updateId={})",
                        clusterName, nodeGroupName, minSize, updateId);

                VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                return VmOperationResult.success(updateId, finalStatus);

            } catch (EksException e) {
                // Node group may already be updating (prior request in flight) — check actual state
                log.error("EKS error starting node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("EKS node group {}/{} already transitioning ({}) — waiting to complete",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                        return VmOperationResult.success("recovered", finalStatus);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("EKS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Error starting EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("EKS node group {}/{} is {} despite exception — waiting to complete",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                        return VmOperationResult.success("recovered", finalStatus);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force) {
        return CompletableFuture.supplyAsync(() -> {
            String[] parts = parseProviderVmId(providerVmId);
            if (parts == null) {
                return VmOperationResult.failure("Invalid providerVmId format (expected clusterName/nodeGroupName): " + providerVmId);
            }
            String clusterName = parts[0];
            String nodeGroupName = parts[1];

            EksClient eks = getEksClient(region);
            try {
                // Persist current scaling config BEFORE zeroing so startVm can restore it
                saveScalingConfigToMetadata(providerVmId, eks, clusterName, nodeGroupName);

                UpdateNodegroupConfigRequest request = UpdateNodegroupConfigRequest.builder()
                        .clusterName(clusterName)
                        .nodegroupName(nodeGroupName)
                        .scalingConfig(NodegroupScalingConfig.builder()
                                .minSize(0)
                                .desiredSize(0)
                                .build())
                        .build();

                UpdateNodegroupConfigResponse response = eks.updateNodegroupConfig(request);
                String updateId = response.update() != null ? response.update().id() : "unknown";

                log.info("EKS node group {}/{} scale-down to 0 requested (updateId={})",
                        clusterName, nodeGroupName, updateId);

                VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, false);
                return VmOperationResult.success(updateId, finalStatus);

            } catch (EksException e) {
                // Node group may already be updating — check whether it's scaling down
                log.error("EKS error stopping node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("EKS node group {}/{} already transitioning to stopped ({}) — waiting",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, false);
                        return VmOperationResult.success("recovered", finalStatus);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("EKS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Error stopping EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, region);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("EKS node group {}/{} is {} despite exception — waiting to complete",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, false);
                        return VmOperationResult.success("recovered", finalStatus);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        });
    }

    @Override
    public VmStatus getVmStatus(String providerVmId, String region) {
        String[] parts = parseProviderVmId(providerVmId);
        if (parts == null) {
            log.warn("Invalid providerVmId for EKS status check: {}", providerVmId);
            return VmStatus.UNKNOWN;
        }
        String clusterName = parts[0];
        String nodeGroupName = parts[1];

        try {
            EksClient eks = getEksClient(region);
            DescribeNodegroupResponse response = eks.describeNodegroup(
                    DescribeNodegroupRequest.builder()
                            .clusterName(clusterName)
                            .nodegroupName(nodeGroupName)
                            .build());
            return mapNodegroupToVmStatus(response.nodegroup());

        } catch (ResourceNotFoundException e) {
            log.warn("EKS node group {}/{} not found", clusterName, nodeGroupName);
            return VmStatus.NOT_FOUND;
        } catch (Exception e) {
            log.error("Error fetching EKS node group status {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
            return VmStatus.UNKNOWN;
        }
    }

    @Override
    public String getVmName(String providerVmId, String region) {
        String[] parts = parseProviderVmId(providerVmId);
        return parts != null ? parts[1] : providerVmId;
    }

    /**
     * Lists all node group names for the given EKS cluster.
     */
    public List<String> listNodegroups(String clusterName, String region) {
        try {
            EksClient eks = getEksClient(region);
            ListNodegroupsResponse response = eks.listNodegroups(
                    ListNodegroupsRequest.builder().clusterName(clusterName).build());
            return response.nodegroups();
        } catch (Exception e) {
            log.error("Error listing EKS node groups for cluster {}: {}", clusterName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Describes a specific node group. Used by EksSyncService.
     */
    public Nodegroup describeNodegroup(String clusterName, String nodeGroupName, String region) {
        try {
            EksClient eks = getEksClient(region);
            DescribeNodegroupResponse response = eks.describeNodegroup(
                    DescribeNodegroupRequest.builder()
                            .clusterName(clusterName)
                            .nodegroupName(nodeGroupName)
                            .build());
            return response.nodegroup();
        } catch (ResourceNotFoundException e) {
            return null;
        } catch (Exception e) {
            log.error("Error describing EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
            return null;
        }
    }

    /**
     * Lists all EKS cluster names in a region.
     */
    public List<String> listClusters(String region) {
        try {
            EksClient eks = getEksClient(region);
            return eks.listClusters().clusters();
        } catch (Exception e) {
            log.error("Error listing EKS clusters in region {}: {}", region, e.getMessage());
            return List.of();
        }
    }

    // ---- private helpers ----

    /**
     * Reads the saved minSize from Vm.metadata written by a previous stopVm.
     * Falls back to the defaultMinSize property when no saved value exists.
     */
    private int readSavedMinSize(String providerVmId) {
        try {
            Optional<Vm> vmOpt = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, providerVmId);
            if (vmOpt.isPresent() && vmOpt.get().getMetadata() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = objectMapper.readValue(vmOpt.get().getMetadata(), Map.class);
                Object val = meta.get("minSize");
                if (val instanceof Number n) {
                    int saved = n.intValue();
                    return saved > 0 ? saved : defaultMinSize;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read saved minSize for EKS VM {}, using default {}: {}",
                    providerVmId, defaultMinSize, e.getMessage());
        }
        return defaultMinSize;
    }

    /**
     * Describes the live node group and writes {minSize, desiredSize} to Vm.metadata.
     * Skips the write if the node group is already at zero (nothing useful to save).
     * If anything fails, logs a warning and returns — the stop proceeds regardless.
     */
    private void saveScalingConfigToMetadata(String providerVmId, EksClient eks,
                                              String clusterName, String nodeGroupName) {
        try {
            DescribeNodegroupResponse desc = eks.describeNodegroup(
                    DescribeNodegroupRequest.builder()
                            .clusterName(clusterName)
                            .nodegroupName(nodeGroupName)
                            .build());

            Nodegroup ng = desc.nodegroup();
            if (ng == null || ng.scalingConfig() == null) return;

            int liveMin = ng.scalingConfig().minSize();
            int liveDesired = ng.scalingConfig().desiredSize();

            if (liveDesired <= 0 && liveMin <= 0) {
                log.debug("EKS node group {}/{} already at zero — skipping metadata save", clusterName, nodeGroupName);
                return;
            }

            int saveMin = liveMin > 0 ? liveMin : defaultMinSize;
            String metaJson = objectMapper.writeValueAsString(
                    Map.of("minSize", saveMin, "desiredSize", liveDesired));

            Optional<Vm> vmOpt = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, providerVmId);
            vmOpt.ifPresentOrElse(vm -> {
                vm.setMetadata(metaJson);
                vmRepository.save(vm);
                log.info("Saved EKS scaling config before stop for {}: minSize={}, desiredSize={}",
                        providerVmId, saveMin, liveDesired);
            }, () -> log.warn("EKS VM not found in DB for metadata save: {}", providerVmId));

        } catch (Exception e) {
            log.warn("Failed to save scaling config for EKS VM {} (stop will continue): {}",
                    providerVmId, e.getMessage());
        }
    }

    /**
     * Polls until the node group reaches a stable state.
     *
     * @param expectRunning true = waiting for start (desired >= 1); false = waiting for stop (desired = 0)
     */
    private VmStatus waitForNodegroupActive(EksClient eks, String clusterName,
                                             String nodeGroupName, boolean expectRunning) {
        long startTime = System.currentTimeMillis();
        VmStatus lastStatus = expectRunning ? VmStatus.STARTING : VmStatus.STOPPING;

        while (System.currentTimeMillis() - startTime < operationTimeoutMs) {
            try {
                if (pollIntervalMs > 0) Thread.sleep(pollIntervalMs);

                DescribeNodegroupResponse response = eks.describeNodegroup(
                        DescribeNodegroupRequest.builder()
                                .clusterName(clusterName)
                                .nodegroupName(nodeGroupName)
                                .build());

                Nodegroup ng = response.nodegroup();
                lastStatus = mapNodegroupToVmStatus(ng);

                log.debug("EKS node group {}/{} — status={}, desired={}",
                        clusterName, nodeGroupName, ng.status(),
                        ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : "?");

                if (ng.status() == NodegroupStatus.ACTIVE) {
                    int desired = ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : -1;
                    if (expectRunning && desired >= 1) return VmStatus.RUNNING;
                    if (!expectRunning && desired == 0) return VmStatus.STOPPED;
                }

                if (ng.status() == NodegroupStatus.CREATE_FAILED
                        || ng.status() == NodegroupStatus.DELETE_FAILED
                        || ng.status() == NodegroupStatus.DEGRADED) {
                    log.warn("EKS node group {}/{} reached terminal error state: {}",
                            clusterName, nodeGroupName, ng.status());
                    return VmStatus.ERROR;
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return lastStatus;
            } catch (Exception e) {
                log.warn("Error polling EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
            }
        }

        log.warn("Timed out waiting for EKS node group {}/{} after {}ms",
                clusterName, nodeGroupName, operationTimeoutMs);
        return lastStatus;
    }

    // package-private for testing
    VmStatus mapNodegroupToVmStatus(Nodegroup ng) {
        if (ng == null) return VmStatus.NOT_FOUND;
        NodegroupStatus status = ng.status();
        int desired = ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : -1;

        if (status == NodegroupStatus.ACTIVE) {
            return desired > 0 ? VmStatus.RUNNING : VmStatus.STOPPED;
        } else if (status == NodegroupStatus.CREATING || status == NodegroupStatus.UPDATING) {
            return VmStatus.STARTING;
        } else if (status == NodegroupStatus.DELETING || status == NodegroupStatus.DEGRADED) {
            return VmStatus.STOPPING;
        } else if (status == NodegroupStatus.CREATE_FAILED || status == NodegroupStatus.DELETE_FAILED) {
            return VmStatus.ERROR;
        }
        return VmStatus.UNKNOWN;
    }

    private String[] parseProviderVmId(String providerVmId) {
        if (providerVmId == null) return null;
        int slash = providerVmId.indexOf('/');
        if (slash <= 0 || slash >= providerVmId.length() - 1) return null;
        return new String[]{providerVmId.substring(0, slash), providerVmId.substring(slash + 1)};
    }

    private EksClient getEksClient(String region) {
        String effectiveRegion = region != null && !region.isEmpty() ? region : defaultRegion;
        return clientCache.computeIfAbsent(effectiveRegion, r -> EksClient.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build());
    }
}
