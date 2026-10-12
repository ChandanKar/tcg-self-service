package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbClusterNotFoundException;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.Filter;
import software.amazon.awssdk.services.rds.model.InvalidDbClusterStateException;
import software.amazon.awssdk.services.rds.model.InvalidDbInstanceStateException;
import software.amazon.awssdk.services.rds.model.StartDbClusterRequest;
import software.amazon.awssdk.services.rds.model.StartDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.StopDbClusterRequest;
import software.amazon.awssdk.services.rds.model.StopDbInstanceRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RDS DB instances and Aurora clusters as VMs (E33-T01): provider AWS_RDS, providerVmId
 * {@code db:<DBInstanceIdentifier>} or {@code cluster:<DBClusterIdentifier>}. Start and stop call
 * StartDBInstance/StopDBInstance or StartDBCluster/StopDBCluster and poll until the database reports
 * available / stopped. Off (rds.enabled) it is unavailable and nothing reaches AWS.
 */
@Service
public class RdsCloudProviderService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(RdsCloudProviderService.class);

    public static final String INSTANCE_PREFIX = "db:";
    public static final String CLUSTER_PREFIX = "cluster:";
    private static final int FILTER_CHUNK = 100;

    @Value("${rds.enabled:false}")
    private boolean enabled;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    @Value("${rds.poll-interval-ms:20000}")
    private long pollIntervalMs;

    @Value("${rds.operation-timeout-ms:1800000}")
    private long operationTimeoutMs;

    @Autowired(required = false)
    private SettingsService settings;

    private final AwsClientFactory awsClientFactory;
    private final Executor executor;

    public RdsCloudProviderService(AwsClientFactory awsClientFactory,
                                   @Qualifier("rdsOperationExecutor") Executor executor) {
        this.awsClientFactory = awsClientFactory;
        this.executor = executor;
    }

    /** A parsed providerVmId: an instance or a cluster and its identifier. */
    public record RdsId(boolean cluster, String identifier) {
        public String providerVmId() {
            return (cluster ? CLUSTER_PREFIX : INSTANCE_PREFIX) + identifier;
        }
    }

    /** The providerVmId's kind and identifier, or null when it has neither prefix or no identifier. */
    public static RdsId parse(String providerVmId) {
        if (providerVmId == null) return null;
        String id = providerVmId.trim();
        if (id.startsWith(INSTANCE_PREFIX) && id.length() > INSTANCE_PREFIX.length()) {
            return new RdsId(false, id.substring(INSTANCE_PREFIX.length()));
        }
        if (id.startsWith(CLUSTER_PREFIX) && id.length() > CLUSTER_PREFIX.length()) {
            return new RdsId(true, id.substring(CLUSTER_PREFIX.length()));
        }
        return null;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS_RDS;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("rds.enabled", enabled);
    }

    @Override
    public boolean isAvailable() {
        return isEnabled() && awsClientFactory.isConfigured();
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, CloudTarget target) {
        return startVm(providerVmId, target, null);
    }

    @Override
    public CompletableFuture<VmOperationResult> startVm(String providerVmId, CloudTarget target,
                                                        OperationProgressListener progressListener) {
        return CompletableFuture.supplyAsync(() -> operate(providerVmId, target, true, progressListener), executor);
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, CloudTarget target, boolean force) {
        return stopVm(providerVmId, target, force, null);
    }

    @Override
    public CompletableFuture<VmOperationResult> stopVm(String providerVmId, CloudTarget target, boolean force,
                                                       OperationProgressListener progressListener) {
        return CompletableFuture.supplyAsync(() -> operate(providerVmId, target, false, progressListener), executor);
    }

    private VmOperationResult operate(String providerVmId, CloudTarget target, boolean start,
                                      OperationProgressListener listener) {
        if (!isAvailable()) {
            return VmOperationResult.failure("RDS support is not enabled (rds.enabled)");
        }
        RdsId id = parse(providerVmId);
        if (id == null) {
            return VmOperationResult.failure("Invalid RDS id (expected db:<instance> or cluster:<cluster>): " + providerVmId);
        }
        String goal = start ? "available" : "stopped";
        RdsClient rds = client(target);
        try {
            String status;
            if (id.cluster()) {
                DBCluster cluster = describeCluster(rds, id.identifier());
                if (cluster == null) return VmOperationResult.failure("Aurora cluster " + id.identifier() + " not found");
                String restriction = start ? null : stopRestriction(cluster);
                if (restriction != null) return VmOperationResult.failure(restriction);
                status = lower(cluster.status());
            } else {
                DBInstance instance = describeInstance(rds, id.identifier());
                if (instance == null) return VmOperationResult.failure("DB instance " + id.identifier() + " not found");
                String restriction = stopRestriction(instance);
                if (restriction != null && (!start || instance.dbClusterIdentifier() != null)) {
                    return VmOperationResult.failure(restriction);
                }
                status = lower(instance.dbInstanceStatus());
            }
            if (goal.equals(status)) {
                return VmOperationResult.success("already-" + goal, start ? VmStatus.RUNNING : VmStatus.STOPPED);
            }
            if (!status.equals(start ? "starting" : "stopping")) {
                call(rds, id, start);
                log.info("RDS {} {} requested in {}", id.providerVmId(), start ? "start" : "stop", target);
            }
        } catch (InvalidDbInstanceStateException | InvalidDbClusterStateException e) {
            // Already there or on its way (another request got in first): wait; anything else fails.
            String now = currentStatus(rds, id);
            if (!goal.equals(now) && !(start ? "starting" : "stopping").equals(now)) {
                return VmOperationResult.failure("RDS refused the " + (start ? "start" : "stop") + " while the database is "
                        + (now == null ? "in another state" : now) + ": " + message(e));
            }
        } catch (AwsServiceException e) {
            log.error("RDS {} of {} failed: {}", start ? "start" : "stop", id.providerVmId(), message(e));
            return VmOperationResult.failure("RDS error: " + message(e));
        } catch (Exception e) {
            log.error("RDS {} of {} failed: {}", start ? "start" : "stop", id.providerVmId(), e.getMessage());
            return VmOperationResult.failure("Error: " + e.getMessage());
        }
        return waitFor(rds, id, start, listener);
    }

    private void call(RdsClient rds, RdsId id, boolean start) {
        if (id.cluster()) {
            if (start) rds.startDBCluster(StartDbClusterRequest.builder().dbClusterIdentifier(id.identifier()).build());
            else rds.stopDBCluster(StopDbClusterRequest.builder().dbClusterIdentifier(id.identifier()).build());
        } else {
            if (start) rds.startDBInstance(StartDbInstanceRequest.builder().dbInstanceIdentifier(id.identifier()).build());
            else rds.stopDBInstance(StopDbInstanceRequest.builder().dbInstanceIdentifier(id.identifier()).build());
        }
    }

    /** Polls until the goal status, a status the operation cannot come back from, or the time-out. */
    private VmOperationResult waitFor(RdsClient rds, RdsId id, boolean start, OperationProgressListener listener) {
        String goal = start ? "available" : "stopped";
        long began = System.currentTimeMillis();
        String last = null;
        do {
            try {
                if (pollIntervalMs > 0) Thread.sleep(pollIntervalMs);
                last = currentStatus(rds, id);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return VmOperationResult.timedOut("Interrupted while waiting for " + id.providerVmId(), mapStatus(last));
            } catch (Exception e) {
                log.warn("RDS status poll of {} failed: {}", id.providerVmId(), e.getMessage());
                continue;
            }
            if (last == null) {
                return VmOperationResult.failure("The database " + id.identifier() + " no longer exists");
            }
            if (goal.equals(last)) {
                return VmOperationResult.success(id.providerVmId(), start ? VmStatus.RUNNING : VmStatus.STOPPED);
            }
            VmStatus mapped = mapStatus(last);
            if (mapped == VmStatus.ERROR || mapped == VmStatus.TERMINATED) {
                return VmOperationResult.failure("The database is " + last);
            }
            if (listener != null) {
                long elapsed = System.currentTimeMillis() - began;
                int pct = (int) Math.min(90, 10 + (operationTimeoutMs > 0 ? elapsed * 80 / operationTimeoutMs : 0));
                listener.onProgress(VmOperationProgress.of(mapped == VmStatus.UNKNOWN ? (start ? VmStatus.STARTING : VmStatus.STOPPING) : mapped,
                        "Database " + last, pct));
            }
        } while (System.currentTimeMillis() - began < operationTimeoutMs);
        return VmOperationResult.timedOut("Database still " + last + " when polling stopped", mapStatus(last));
    }

    /** The lower-cased status, or null when the database does not exist. */
    private String currentStatus(RdsClient rds, RdsId id) {
        if (id.cluster()) {
            DBCluster c = describeCluster(rds, id.identifier());
            return c == null ? null : lower(c.status());
        }
        DBInstance i = describeInstance(rds, id.identifier());
        return i == null ? null : lower(i.dbInstanceStatus());
    }

    private DBInstance describeInstance(RdsClient rds, String identifier) {
        try {
            List<DBInstance> found = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                    .dbInstanceIdentifier(identifier).build()).dbInstances();
            return found.isEmpty() ? null : found.get(0);
        } catch (DbInstanceNotFoundException e) {
            return null;
        }
    }

    private DBCluster describeCluster(RdsClient rds, String identifier) {
        try {
            List<DBCluster> found = rds.describeDBClusters(DescribeDbClustersRequest.builder()
                    .dbClusterIdentifier(identifier).build()).dbClusters();
            return found.isEmpty() ? null : found.get(0);
        } catch (DbClusterNotFoundException e) {
            return null;
        }
    }

    /**
     * Why this instance cannot be stopped on its own, or null: a member of an Aurora cluster (stop the
     * cluster), a read replica, or SQL Server in Multi-AZ.
     */
    public static String stopRestriction(DBInstance instance) {
        if (instance.dbClusterIdentifier() != null) {
            return "DB instance " + instance.dbInstanceIdentifier() + " belongs to Aurora cluster "
                    + instance.dbClusterIdentifier() + "; register and operate the cluster instead";
        }
        if (instance.readReplicaSourceDBInstanceIdentifier() != null) {
            return "DB instance " + instance.dbInstanceIdentifier() + " is a read replica, which RDS cannot stop";
        }
        String engine = lower(instance.engine());
        if (engine.startsWith("sqlserver") && Boolean.TRUE.equals(instance.multiAZ())) {
            return "DB instance " + instance.dbInstanceIdentifier() + " is SQL Server in Multi-AZ, which RDS cannot stop";
        }
        return null;
    }

    /** Why this cluster cannot be stopped, or null: Aurora Serverless v1 clusters cannot be stopped. */
    public static String stopRestriction(DBCluster cluster) {
        if ("serverless".equals(lower(cluster.engineMode()))) {
            return "Aurora cluster " + cluster.dbClusterIdentifier() + " is Aurora Serverless v1, which cannot be stopped";
        }
        return null;
    }

    /**
     * RDS status to VmStatus: available, and the maintenance states a running database passes
     * through (backing-up, modifying, upgrading, maintenance, configuring-*, …), are RUNNING so a
     * routine backup is not taken for drift; failed and inaccessible states are ERROR.
     */
    public static VmStatus mapStatus(String status) {
        if (status == null) return VmStatus.UNKNOWN;
        String s = status.toLowerCase(Locale.ROOT);
        switch (s) {
            case "available":
            case "backing-up":
            case "modifying":
            case "upgrading":
            case "maintenance":
            case "rebooting":
            case "renaming":
            case "resetting-master-credentials":
            case "storage-optimization":
            case "storage-full":
            case "moving-to-vpc":
            case "failing-over":
            case "backtracking":
            case "promoting":
            case "migrating":
            case "update-iam-db-auth":
                return VmStatus.RUNNING;
            case "stopped":
                return VmStatus.STOPPED;
            case "starting":
                return VmStatus.STARTING;
            case "stopping":
                return VmStatus.STOPPING;
            case "deleting":
            case "deleted":
                return VmStatus.TERMINATED;
            case "failed":
            case "restore-error":
            case "cloning-failed":
                return VmStatus.ERROR;
            default:
                if (s.startsWith("configuring-")) return VmStatus.RUNNING;
                if (s.startsWith("incompatible-") || s.startsWith("inaccessible-")) return VmStatus.ERROR;
                return VmStatus.UNKNOWN;
        }
    }

    @Override
    public VmStatus getVmStatus(String providerVmId, CloudTarget target) {
        if (!isAvailable()) return VmStatus.UNKNOWN;
        RdsId id = parse(providerVmId);
        if (id == null) {
            log.warn("Invalid providerVmId for an RDS status check: {}", providerVmId);
            return VmStatus.UNKNOWN;
        }
        try {
            String status = currentStatus(client(target), id);
            return status == null ? VmStatus.NOT_FOUND : mapStatus(status);
        } catch (Exception e) {
            log.error("Error fetching RDS status of {}: {}", providerVmId, e.getMessage());
            return VmStatus.UNKNOWN;
        }
    }

    /**
     * One DescribeDBInstances (filtered by db-instance-id, 100 a call) and one DescribeDBClusters
     * per target; ids missing from a successful answer are NOT_FOUND. A failed call returns what was
     * read so far, and the caller falls back to single lookups for the rest.
     */
    @Override
    public Map<String, VmStatus> getVmStatusBatch(List<String> providerVmIds, CloudTarget target) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) return Collections.emptyMap();
        List<String> instances = new ArrayList<>();
        List<String> clusters = new ArrayList<>();
        for (String raw : providerVmIds) {
            RdsId id = parse(raw);
            if (id != null) (id.cluster() ? clusters : instances).add(id.identifier());
        }
        Map<String, VmStatus> result = new HashMap<>();
        RdsClient rds = client(target);
        try {
            for (int i = 0; i < instances.size(); i += FILTER_CHUNK) {
                List<String> chunk = instances.subList(i, Math.min(i + FILTER_CHUNK, instances.size()));
                String marker = null;
                do {
                    DescribeDbInstancesResponse page = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                            .filters(Filter.builder().name("db-instance-id").values(chunk).build()).marker(marker).build());
                    page.dbInstances().forEach(db -> result.put(INSTANCE_PREFIX + db.dbInstanceIdentifier(), mapStatus(db.dbInstanceStatus())));
                    marker = page.marker();
                } while (marker != null && !marker.isBlank());
                chunk.forEach(name -> result.putIfAbsent(INSTANCE_PREFIX + name, VmStatus.NOT_FOUND));
            }
            for (int i = 0; i < clusters.size(); i += FILTER_CHUNK) {
                List<String> chunk = clusters.subList(i, Math.min(i + FILTER_CHUNK, clusters.size()));
                String marker = null;
                do {
                    DescribeDbClustersResponse page = rds.describeDBClusters(DescribeDbClustersRequest.builder()
                            .filters(Filter.builder().name("db-cluster-id").values(chunk).build()).marker(marker).build());
                    page.dbClusters().forEach(c -> result.put(CLUSTER_PREFIX + c.dbClusterIdentifier(), mapStatus(c.status())));
                    marker = page.marker();
                } while (marker != null && !marker.isBlank());
                chunk.forEach(name -> result.putIfAbsent(CLUSTER_PREFIX + name, VmStatus.NOT_FOUND));
            }
        } catch (Exception e) {
            log.error("Batch RDS status fetch failed in {}: {}", target, e.getMessage());
        }
        return result;
    }

    /**
     * The DB instances and DB clusters of a target for registration (E33-T02). Instances that belong
     * to a cluster are left out: the cluster is what is started and stopped. Failures propagate.
     */
    public List<RdsDatabaseInfoDTO> listDatabases(CloudTarget target) {
        RdsClient rds = client(target);
        List<RdsDatabaseInfoDTO> result = new ArrayList<>();
        String marker = null;
        do {
            DescribeDbClustersResponse page = rds.describeDBClusters(DescribeDbClustersRequest.builder().marker(marker).build());
            for (DBCluster c : page.dbClusters()) {
                String reason = stopRestriction(c);
                result.add(new RdsDatabaseInfoDTO(CLUSTER_PREFIX + c.dbClusterIdentifier(), c.dbClusterIdentifier(), "CLUSTER",
                        c.engine(), c.engineVersion(), c.dbClusterInstanceClass(), c.status(), Boolean.TRUE.equals(c.multiAZ()),
                        reason == null, reason, false));
            }
            marker = page.marker();
        } while (marker != null && !marker.isBlank());
        marker = null;
        do {
            DescribeDbInstancesResponse page = rds.describeDBInstances(DescribeDbInstancesRequest.builder().marker(marker).build());
            for (DBInstance db : page.dbInstances()) {
                if (db.dbClusterIdentifier() != null) continue;
                String reason = stopRestriction(db);
                result.add(new RdsDatabaseInfoDTO(INSTANCE_PREFIX + db.dbInstanceIdentifier(), db.dbInstanceIdentifier(), "INSTANCE",
                        db.engine(), db.engineVersion(), db.dbInstanceClass(), db.dbInstanceStatus(), Boolean.TRUE.equals(db.multiAZ()),
                        reason == null, reason, false));
            }
            marker = page.marker();
        } while (marker != null && !marker.isBlank());
        return result;
    }

    @Override
    public String getVmName(String providerVmId, CloudTarget target) {
        RdsId id = parse(providerVmId);
        return id == null ? providerVmId : id.identifier();
    }

    /** The target account's cached RDS client in the target region (the default region when blank). */
    RdsClient client(CloudTarget target) {
        String region = target.region() != null && !target.region().isBlank() ? target.region() : defaultRegion;
        return awsClientFactory.rds(target.accountRef(), region);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String message(AwsServiceException e) {
        return e.awsErrorDetails() != null && e.awsErrorDetails().errorMessage() != null
                ? e.awsErrorDetails().errorMessage() : e.getMessage();
    }
}
