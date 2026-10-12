package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    @Value("${eks.nodegroup.default-min-size:1}")
    private int defaultMinSize;

    @Value("${eks.nodegroup.poll-interval-ms:15000}")
    private long pollIntervalMs;

    @Value("${eks.nodegroup.operation-timeout-ms:900000}")
    private long operationTimeoutMs;

    private final VmRepository vmRepository;
    private final ObjectMapper objectMapper;
    private final Executor eksOperationExecutor;
    private final AwsClientFactory awsClientFactory;

    public EksCloudProviderService(VmRepository vmRepository, ObjectMapper objectMapper,
                                   @Qualifier("eksOperationExecutor") Executor eksOperationExecutor,
                                   AwsClientFactory awsClientFactory) {
        this.eksOperationExecutor = eksOperationExecutor;
        this.awsClientFactory = awsClientFactory;
        this.vmRepository = vmRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS_EKS;
    }

    @Override
    public boolean isAvailable() {
        return awsClientFactory.isConfigured();
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, CloudTarget target) {
        return CompletableFuture.supplyAsync(() -> {
            String[] parts = parseProviderVmId(providerVmId);
            if (parts == null) {
                return VmOperationResult.failure("Invalid providerVmId format (expected clusterName/nodeGroupName): " + providerVmId);
            }
            String clusterName = parts[0];
            String nodeGroupName = parts[1];

            // Restore the sizes saved at stop: min as saved, desired = max(saved desired, min).
            int minSize = readSavedMinSize(providerVmId);
            int desiredSize = Math.max(readSavedDesiredSize(providerVmId), minSize);

            EksClient eks = getEksClient(target);
            try {
                UpdateNodegroupConfigRequest request = UpdateNodegroupConfigRequest.builder()
                        .clusterName(clusterName)
                        .nodegroupName(nodeGroupName)
                        .scalingConfig(NodegroupScalingConfig.builder()
                                .minSize(minSize)
                                .desiredSize(desiredSize)
                                .build())
                        .build();

                UpdateNodegroupConfigResponse response = eks.updateNodegroupConfig(request);
                String updateId = response.update() != null ? response.update().id() : "unknown";

                log.info("EKS node group {}/{} scale-up to min {} / desired {} requested (updateId={})",
                        clusterName, nodeGroupName, minSize, desiredSize, updateId);

                VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                return nodegroupResult(updateId, finalStatus, true);

            } catch (EksException e) {
                // Node group may already be updating (prior request in flight) — check actual state
                log.error("EKS error starting node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, target);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("EKS node group {}/{} already transitioning ({}) — waiting to complete",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                        return nodegroupResult("recovered", finalStatus, true);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("EKS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Error starting EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, target);
                    if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.RUNNING) {
                        log.info("EKS node group {}/{} is {} despite exception — waiting to complete",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, true);
                        return nodegroupResult("recovered", finalStatus, true);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        }, eksOperationExecutor);
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, CloudTarget target, boolean force) {
        return CompletableFuture.supplyAsync(() -> {
            String[] parts = parseProviderVmId(providerVmId);
            if (parts == null) {
                return VmOperationResult.failure("Invalid providerVmId format (expected clusterName/nodeGroupName): " + providerVmId);
            }
            String clusterName = parts[0];
            String nodeGroupName = parts[1];

            EksClient eks = getEksClient(target);
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
                return nodegroupResult(updateId, finalStatus, false);

            } catch (EksException e) {
                // Node group may already be updating — check whether it's scaling down
                log.error("EKS error stopping node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, target);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("EKS node group {}/{} already transitioning to stopped ({}) — waiting",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, false);
                        return nodegroupResult("recovered", finalStatus, false);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("EKS Error: " + e.awsErrorDetails().errorMessage());
            } catch (Exception e) {
                log.error("Error stopping EKS node group {}/{}: {}", clusterName, nodeGroupName, e.getMessage());
                try {
                    VmStatus currentStatus = getVmStatus(providerVmId, target);
                    if (currentStatus == VmStatus.STOPPING || currentStatus == VmStatus.STOPPED) {
                        log.info("EKS node group {}/{} is {} despite exception — waiting to complete",
                                clusterName, nodeGroupName, currentStatus);
                        VmStatus finalStatus = waitForNodegroupActive(eks, clusterName, nodeGroupName, false);
                        return nodegroupResult("recovered", finalStatus, false);
                    }
                } catch (Exception inner) {
                    log.warn("Could not verify EKS node group state after error: {}", inner.getMessage());
                }
                return VmOperationResult.failure("Error: " + e.getMessage());
            }
        }, eksOperationExecutor);
    }

    @Override
    public VmStatus getVmStatus(String providerVmId, CloudTarget target) {
        String[] parts = parseProviderVmId(providerVmId);
        if (parts == null) {
            log.warn("Invalid providerVmId for EKS status check: {}", providerVmId);
            return VmStatus.UNKNOWN;
        }
        String clusterName = parts[0];
        String nodeGroupName = parts[1];

        try {
            EksClient eks = getEksClient(target);
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
    public String getVmName(String providerVmId, CloudTarget target) {
        String[] parts = parseProviderVmId(providerVmId);
        return parts != null ? parts[1] : providerVmId;
    }

    /**
     * Lists all node group names for the given EKS cluster.
     * Propagates failures rather than swallowing them — a mid-pagination error must not
     * silently discard pages already fetched and masquerade as "cluster has no node groups".
     */
    public List<String> listNodegroups(String clusterName, CloudTarget target) {
        EksClient eks = getEksClient(target);
        List<String> nodegroups = new ArrayList<>();
        String nextToken = null;

        do {
            ListNodegroupsResponse response = eks.listNodegroups(
                    ListNodegroupsRequest.builder()
                            .clusterName(clusterName)
                            .maxResults(100)
                            .nextToken(nextToken)
                            .build());
            nodegroups.addAll(response.nodegroups());
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isBlank());

        return nodegroups;
    }

    /**
     * Describes a specific node group. Used by EksSyncService.
     */
    public Nodegroup describeNodegroup(String clusterName, String nodeGroupName, CloudTarget target) {
        try {
            EksClient eks = getEksClient(target);
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
     * Propagates failures rather than swallowing them — a mid-pagination error must not
     * silently discard pages already fetched and masquerade as "no clusters in this region".
     */
    public List<String> listClusters(CloudTarget target) {
        EksClient eks = getEksClient(target);
        List<String> clusters = new ArrayList<>();
        String nextToken = null;

        do {
            ListClustersResponse response = eks.listClusters(
                    ListClustersRequest.builder()
                            .maxResults(100)
                            .nextToken(nextToken)
                            .build());
            clusters.addAll(response.clusters());
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isBlank());

        return clusters;
    }

    /**
     * Applies (or overwrites) the given tags on an EKS managed node group, identified by its
     * ARN (not clusterName/nodeGroupName — {@code TagResource} requires the real ARN, obtained
     * via {@link #describeNodegroup}). Used for cost-allocation tagging, never called during
     * normal start/stop/sync.
     */
    public void tagNodeGroup(String nodegroupArn, CloudTarget target, Map<String, String> tags) {
        if (nodegroupArn == null || tags.isEmpty()) {
            return;
        }
        EksClient eks = getEksClient(target);
        eks.tagResource(TagResourceRequest.builder()
                .resourceArn(nodegroupArn)
                .tags(tags)
                .build());
        log.info("Tagged EKS node group {} with {}", nodegroupArn, tags);
    }

    // ---- private helpers ----

    /**
     * A node-group start/stop succeeded only when it reached RUNNING / STOPPED; an error state is
     * a failure and anything else (still scaling at the poll timeout) a timeout (M9).
     */
    private VmOperationResult nodegroupResult(String requestId, VmStatus finalStatus, boolean expectRunning) {
        VmStatus target = expectRunning ? VmStatus.RUNNING : VmStatus.STOPPED;
        if (finalStatus == target) {
            return VmOperationResult.success(requestId, finalStatus);
        }
        if (finalStatus == VmStatus.ERROR) {
            return VmOperationResult.failure("Node group entered error state");
        }
        return VmOperationResult.timedOut("Node group still " + finalStatus + " when polling stopped", finalStatus);
    }

    /**
     * Reads the saved minSize from Vm.metadata written by a previous stopVm.
     * Falls back to the defaultMinSize property when no saved value exists.
     */
    /** The desiredSize saved by a previous stopVm, or 0 when none was saved. */
    private int readSavedDesiredSize(String providerVmId) {
        try {
            Optional<Vm> vmOpt = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, providerVmId);
            if (vmOpt.isPresent() && vmOpt.get().getMetadata() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = objectMapper.readValue(vmOpt.get().getMetadata(), Map.class);
                if (meta.get("desiredSize") instanceof Number n) {
                    return Math.max(n.intValue(), 0);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read saved desiredSize for EKS VM {}: {}", providerVmId, e.getMessage());
        }
        return 0;
    }

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

            Optional<Vm> vmOpt = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, providerVmId);
            if (vmOpt.isEmpty()) {
                log.warn("EKS VM not found in DB for metadata save: {}", providerVmId);
                return;
            }
            // Merge into the existing metadata (keep other keys) and write only that column (H14).
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            String existing = vmOpt.get().getMetadata();
            if (existing != null && !existing.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(existing, Map.class);
                meta.putAll(parsed);
            }
            meta.put("minSize", saveMin);
            meta.put("desiredSize", liveDesired);
            vmRepository.updateMetadata(vmOpt.get().getVmId(), objectMapper.writeValueAsString(meta),
                    java.sql.Timestamp.from(java.time.Instant.now()));
            log.info("Saved EKS scaling config before stop for {}: minSize={}, desiredSize={}",
                    providerVmId, saveMin, liveDesired);

        } catch (Exception e) {
            log.warn("Failed to save scaling config for EKS VM {} (stop will continue): {}",
                    providerVmId, e.getMessage());
        }
    }

    /** Name of a node group's Spot twin: "&lt;source&gt;-spot", within EKS's 63 characters (E31-T05). */
    public static String spotTwinName(String sourceNodegroup) {
        String base = sourceNodegroup.length() > 58 ? sourceNodegroup.substring(0, 58) : sourceNodegroup;
        return base + "-spot";
    }

    /**
     * Creates a SPOT copy of a node group (E31-T05): same subnets, node role, labels, taints,
     * scaling and launch template (or AMI type and disk size), the given diversified instance types,
     * tagged tcg:spot-twin-of; waits until it is ACTIVE with nodes. Returns the twin's name; throws
     * when it does not become ready in time (the caller deletes it).
     */
    public String createSpotTwin(CloudTarget target, String clusterName, String sourceNodegroup, List<String> instanceTypes) {
        EksClient eks = getEksClient(target);
        Nodegroup source = eks.describeNodegroup(DescribeNodegroupRequest.builder()
                .clusterName(clusterName).nodegroupName(sourceNodegroup).build()).nodegroup();
        String twin = spotTwinName(sourceNodegroup);
        java.util.Map<String, String> tags = new java.util.HashMap<>(source.tags() == null ? Map.of() : source.tags());
        tags.put("tcg:spot-twin-of", sourceNodegroup);
        CreateNodegroupRequest.Builder request = CreateNodegroupRequest.builder()
                .clusterName(clusterName)
                .nodegroupName(twin)
                .capacityType(CapacityTypes.SPOT)
                .instanceTypes(instanceTypes)
                .subnets(source.subnets())
                .nodeRole(source.nodeRole())
                .scalingConfig(source.scalingConfig())
                .tags(tags);
        if (source.labels() != null && !source.labels().isEmpty()) {
            request.labels(source.labels());
        }
        if (source.taints() != null && !source.taints().isEmpty()) {
            request.taints(source.taints());
        }
        if (source.launchTemplate() != null) {
            // The node group's types replace the template's: a template that names a type is refused by EKS.
            request.launchTemplate(LaunchTemplateSpecification.builder()
                    .id(source.launchTemplate().id()).version(source.launchTemplate().version()).build());
        } else {
            request.amiType(source.amiType()).diskSize(source.diskSize());
        }
        eks.createNodegroup(request.build());
        log.info("Creating Spot twin {}/{} of {} with types {}", clusterName, twin, sourceNodegroup, instanceTypes);
        VmStatus status = waitForNodegroupActive(eks, clusterName, twin, true);
        if (status != VmStatus.RUNNING) {
            throw new IllegalStateException("Spot node group " + twin + " did not become ready (" + status + ")");
        }
        return twin;
    }

    /** A node group's health for Spot fallback (E31-T06): EKS issue codes, desired and running nodes. */
    public record NodegroupHealth(List<String> issueCodes, int desired, int running) {
    }

    /**
     * Health issues and desired size from DescribeNodegroup, and running nodes counted from the
     * EC2 instances EKS tags with the cluster and node group name. Read-only.
     */
    public NodegroupHealth getNodegroupHealth(CloudTarget target, String clusterName, String nodegroupName) {
        Nodegroup ng = getEksClient(target).describeNodegroup(DescribeNodegroupRequest.builder()
                .clusterName(clusterName).nodegroupName(nodegroupName).build()).nodegroup();
        List<String> issues = ng.health() == null || ng.health().issues() == null ? List.of()
                : ng.health().issues().stream().map(Issue::codeAsString).filter(java.util.Objects::nonNull).toList();
        int desired = ng.scalingConfig() == null || ng.scalingConfig().desiredSize() == null ? 0 : ng.scalingConfig().desiredSize();
        String region = target.region() != null && !target.region().isEmpty() ? target.region() : defaultRegion;
        software.amazon.awssdk.services.ec2.Ec2Client ec2 = awsClientFactory.ec2(target.accountRef(), region);
        int running = 0;
        String token = null;
        do {
            software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse page = ec2.describeInstances(
                    software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest.builder()
                            .filters(software.amazon.awssdk.services.ec2.model.Filter.builder().name("tag:eks:cluster-name").values(clusterName).build(),
                                    software.amazon.awssdk.services.ec2.model.Filter.builder().name("tag:eks:nodegroup-name").values(nodegroupName).build(),
                                    software.amazon.awssdk.services.ec2.model.Filter.builder().name("instance-state-name").values("running").build())
                            .nextToken(token).build());
            for (software.amazon.awssdk.services.ec2.model.Reservation r : page.reservations()) {
                running += r.instances().size();
            }
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        return new NodegroupHealth(issues, desired, running);
    }

    /** Deletes a node group (a Spot twin being removed, E31-T05); does not wait. */
    public void deleteNodegroup(CloudTarget target, String clusterName, String nodegroupName) {
        getEksClient(target).deleteNodegroup(DeleteNodegroupRequest.builder()
                .clusterName(clusterName).nodegroupName(nodegroupName).build());
        log.info("Deleting EKS node group {}/{}", clusterName, nodegroupName);
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
        NodegroupStatus lastNgStatus = null;
        int lastDesired = -1;

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
                lastNgStatus = ng.status();
                lastDesired = ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : -1;

                log.debug("EKS node group {}/{} — status={}, desired={}",
                        clusterName, nodeGroupName, ng.status(), lastDesired);

                if (ng.status() == NodegroupStatus.ACTIVE) {
                    if (expectRunning && lastDesired >= 1) return VmStatus.RUNNING;
                    if (!expectRunning && lastDesired == 0) return VmStatus.STOPPED;
                }

                // Only CREATE_FAILED / DELETE_FAILED are genuinely terminal. DEGRADED is
                // routinely transient while new nodes boot and register during a scale-up —
                // it clears to ACTIVE on its own, so keep polling rather than reporting ERROR.
                if (ng.status() == NodegroupStatus.CREATE_FAILED
                        || ng.status() == NodegroupStatus.DELETE_FAILED) {
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

        log.warn("Timed out waiting for EKS node group {}/{} after {}ms (last status {}, desired {})",
                clusterName, nodeGroupName, operationTimeoutMs, lastNgStatus, lastDesired);
        // The wait window closed, but if the node group is actually up (ACTIVE, or DEGRADED
        // with nodes requested) don't paint it ERROR — the app-visible state is "running".
        if (expectRunning
                && (lastNgStatus == NodegroupStatus.ACTIVE
                    || (lastNgStatus == NodegroupStatus.DEGRADED && lastDesired >= 1))) {
            return VmStatus.RUNNING;
        }
        return lastStatus;
    }

    // package-private for testing
    /**
     * Status for EKS sync, which (unlike operation polling) knows the VM's current status:
     * UPDATING is also a version or config update of a running group, so it means STARTING
     * only for a group that was stopped or starting, and STOPPING when scaled to zero.
     * Every other status maps as {@link #mapNodegroupToVmStatus}.
     */
    VmStatus mapNodegroupForSync(Nodegroup ng, VmStatus current) {
        if (ng != null && ng.status() == NodegroupStatus.UPDATING) {
            int desired = ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : -1;
            if (desired == 0) {
                return VmStatus.STOPPING;
            }
            return current == VmStatus.STOPPED || current == VmStatus.STARTING ? VmStatus.STARTING : VmStatus.RUNNING;
        }
        return mapNodegroupToVmStatus(ng);
    }

    VmStatus mapNodegroupToVmStatus(Nodegroup ng) {
        if (ng == null) return VmStatus.NOT_FOUND;
        NodegroupStatus status = ng.status();
        int desired = ng.scalingConfig() != null ? ng.scalingConfig().desiredSize() : -1;

        if (status == NodegroupStatus.ACTIVE) {
            return desired > 0 ? VmStatus.RUNNING : VmStatus.STOPPED;
        } else if (status == NodegroupStatus.CREATING || status == NodegroupStatus.UPDATING) {
            return VmStatus.STARTING;
        } else if (status == NodegroupStatus.DEGRADED) {
            // Up but unhealthy (often just nodes still registering after a scale-up) when
            // nodes are requested; genuinely winding down when desired is 0.
            return desired > 0 ? VmStatus.RUNNING : VmStatus.STOPPING;
        } else if (status == NodegroupStatus.DELETING) {
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
        return getEksClient(CloudTarget.of(region));
    }

    /** The target account's cached EKS client in the target region (the default region when blank). */
    private EksClient getEksClient(CloudTarget target) {
        String region = target.region() != null && !target.region().isEmpty() ? target.region() : defaultRegion;
        return awsClientFactory.eks(target.accountRef(), region);
    }
}
