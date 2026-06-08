package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmStatus;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS EKS implementation of CloudProviderService.
 *
 * providerVmId convention: "{clusterName}/{nodeGroupName}"
 * region: AWS region string (e.g. "ap-south-1")
 *
 * Start = scale minSize/desiredSize from 0 → 1
 * Stop  = scale minSize/desiredSize from N → 0
 */
@Service
public class EksCloudProviderService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(EksCloudProviderService.class);

    private static final int POLL_INTERVAL_MS  = 15_000;  // 15 seconds
    private static final int OPERATION_TIMEOUT_MS = 300_000; // 5 minutes

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    private final Map<String, EksClient> clientCache = new ConcurrentHashMap<>();

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

            try {
                EksClient eks = getEksClient(region);

                UpdateNodegroupConfigRequest request = UpdateNodegroupConfigRequest.builder()
                        .clusterName(clusterName)
                        .nodegroupName(nodeGroupName)
                        .scalingConfig(NodegroupScalingConfig.builder()
                                .minSize(1)
                                .desiredSize(1)
                                .build())
                        .build();

                UpdateNodegroupConfigResponse response = eks.updateNodegroupConfig(request);
                String updateId = response.update() != null ? response.update().id() : "unknown";

                log.info("EKS node group {}/{} scale-up requested (updateId={})", clusterName, nodeGroupName, updateId);

                VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                return VmOperationResult.success(updateId, finalStatus);

            } catch (EksException e) {
                log.error("EKS error starting node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                return VmOperationResult.failure("EKS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Error starting EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
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

            try {
                EksClient eks = getEksClient(region);

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

                log.info("EKS node group {}/{} scale-down requested (updateId={})", clusterName, nodeGroupName, updateId);

                VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, false);
                return VmOperationResult.success(updateId, finalStatus);

            } catch (EksException e) {
                log.error("EKS error stopping node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                return VmOperationResult.failure("EKS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Error stopping EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
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
     * Used by EksSyncService for discovery.
     */
    public List<String> listNodegroups(String clusterName, String region) {
        try {
            EksClient eks = getEksClient(region);
            ListNodegroupsResponse response = eks.listNodegroups(
                    ListNodegroupsRequest.builder()
                            .clusterName(clusterName)
                            .build());
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

    // ---- private helpers ----

    /**
     * Polls until the node group reaches ACTIVE status after a scale operation.
     * @param expectRunning true=start (wait for nodes up), false=stop (wait for nodes=0)
     */
    private VmStatus waitForNodegroupActive(EksClient eks, String clusterName, String nodeGroupName, boolean expectRunning) {
        long startTime = System.currentTimeMillis();
        VmStatus lastStatus = expectRunning ? VmStatus.STARTING : VmStatus.STOPPING;

        while (System.currentTimeMillis() - startTime < OPERATION_TIMEOUT_MS) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);

                DescribeNodegroupResponse response = eks.describeNodegroup(
                        DescribeNodegroupRequest.builder()
                                .clusterName(clusterName)
                                .nodegroupName(nodeGroupName)
                                .build());

                Nodegroup ng = response.nodegroup();
                lastStatus = mapNodegroupToVmStatus(ng);

                log.debug("EKS node group {}/{} — status={}, desired={}, running={}",
                        clusterName, nodeGroupName,
                        ng.status(),
                        ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : "?",
                        ng.resources() != null ? ng.resources().autoScalingGroups().size() : "?");

                if (ng.status() == NodegroupStatus.ACTIVE) {
                    int desired = ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : -1;
                    if (expectRunning && desired >= 1) return VmStatus.RUNNING;
                    if (!expectRunning && desired == 0) return VmStatus.STOPPED;
                }

                if (ng.status() == NodegroupStatus.CREATE_FAILED || ng.status() == NodegroupStatus.DELETE_FAILED
                        || ng.status() == NodegroupStatus.DEGRADED) {
                    log.warn("EKS node group {}/{} reached terminal error state: {}", clusterName, nodeGroupName, ng.status());
                    return VmStatus.ERROR;
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return lastStatus;
            } catch (Exception e) {
                log.warn("Error polling EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
            }
        }

        log.warn("Timed out waiting for EKS node group {}/{} after {}ms", clusterName, nodeGroupName, OPERATION_TIMEOUT_MS);
        return lastStatus;
    }

    private VmStatus mapNodegroupToVmStatus(Nodegroup ng) {
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

    /**
     * Lists all EKS cluster names in a region.
     * Used by EksSyncService to auto-discover clusters not yet in the DB.
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
