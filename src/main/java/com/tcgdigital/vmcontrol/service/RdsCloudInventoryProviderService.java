package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBClusterMember;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.Filter;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory of AWS_RDS VMs (E33-T03): the DB instance class as the instance type (an Aurora
 * cluster: its writer's class), vCPU and memory from the EC2 type the class is built on, the
 * allocated storage (none for Aurora, whose storage grows on its own), engine and Multi-AZ.
 * Nothing is read while RDS is off.
 */
@Service
public class RdsCloudInventoryProviderService implements CloudInventoryProviderService {

    private static final Logger log = LoggerFactory.getLogger(RdsCloudInventoryProviderService.class);
    private static final int CHUNK = 100;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    private final AwsClientFactory awsClientFactory;
    private final RdsCloudProviderService rdsService;
    private final PricingReferenceService pricingReference;

    public RdsCloudInventoryProviderService(AwsClientFactory awsClientFactory, RdsCloudProviderService rdsService,
                                            PricingReferenceService pricingReference) {
        this.awsClientFactory = awsClientFactory;
        this.rdsService = rdsService;
        this.pricingReference = pricingReference;
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
    public Map<String, VmInventoryData> fetchInventory(List<String> providerVmIds, CloudTarget target) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> instanceIds = new ArrayList<>();
        List<String> clusterIds = new ArrayList<>();
        for (String raw : providerVmIds) {
            RdsCloudProviderService.RdsId id = RdsCloudProviderService.parse(raw);
            if (id != null) (id.cluster() ? clusterIds : instanceIds).add(id.identifier());
        }
        Map<String, VmInventoryData> result = new HashMap<>();
        try {
            RdsClient rds = client(target);
            for (DBInstance db : describeInstances(rds, instanceIds)) {
                VmInventoryData data = fromInstance(db);
                data.setProviderVmId(RdsCloudProviderService.INSTANCE_PREFIX + db.dbInstanceIdentifier());
                data.setStorageGib(db.allocatedStorage());
                result.put(data.getProviderVmId(), data);
            }
            List<DBCluster> clusters = describeClusters(rds, clusterIds);
            List<String> writers = clusters.stream().map(RdsCloudInventoryProviderService::writer)
                    .filter(w -> w != null).toList();
            Map<String, DBInstance> writerInstances = new HashMap<>();
            describeInstances(rds, writers).forEach(db -> writerInstances.put(db.dbInstanceIdentifier(), db));
            for (DBCluster c : clusters) {
                DBInstance w = writerInstances.get(writer(c));
                VmInventoryData data = w != null ? fromInstance(w) : new VmInventoryData();
                data.setProviderVmId(RdsCloudProviderService.CLUSTER_PREFIX + c.dbClusterIdentifier());
                if (w == null) {
                    data.setInstanceType(c.dbClusterInstanceClass());
                    applySpec(data, c.dbClusterInstanceClass());
                }
                data.setEngine(c.engine());
                data.setMultiAz(Boolean.TRUE.equals(c.multiAZ()));
                data.setLaunchTime(c.clusterCreateTime() != null ? Timestamp.from(c.clusterCreateTime()) : null);
                // Aurora storage is not allocated up front; a Multi-AZ DB cluster reports what it has.
                data.setStorageGib(c.engine() != null && c.engine().startsWith("aurora") ? null : c.allocatedStorage());
                result.put(data.getProviderVmId(), data);
            }
        } catch (Exception e) {
            log.error("Failed to fetch RDS inventory for {} database(s) in {}: {}", providerVmIds.size(), target, e.getMessage());
        }
        return result;
    }

    private VmInventoryData fromInstance(DBInstance db) {
        VmInventoryData data = new VmInventoryData();
        data.setInstanceType(db.dbInstanceClass());
        applySpec(data, db.dbInstanceClass());
        data.setEngine(db.engine());
        data.setMultiAz(Boolean.TRUE.equals(db.multiAZ()));
        data.setAvailabilityZone(db.availabilityZone());
        data.setLaunchTime(db.instanceCreateTime() != null ? Timestamp.from(db.instanceCreateTime()) : null);
        return data;
    }

    /** vCPU, memory and architecture of the EC2 type a DB class is built on (db.m5.large is an m5.large). */
    private void applySpec(VmInventoryData data, String dbClass) {
        if (dbClass == null || !dbClass.startsWith("db.") || pricingReference == null) return;
        pricingReference.getSpec(dbClass.substring(3)).ifPresent(spec -> {
            data.setVcpuCount(spec.vcpu());
            data.setMemoryMib(spec.memoryMib() > 0 ? spec.memoryMib() : null);
            data.setArchitecture(spec.architecture());
        });
    }

    private static String writer(DBCluster c) {
        return c.dbClusterMembers().stream().filter(m -> Boolean.TRUE.equals(m.isClusterWriter()))
                .map(DBClusterMember::dbInstanceIdentifier).findFirst().orElse(null);
    }

    private List<DBInstance> describeInstances(RdsClient rds, List<String> identifiers) {
        List<DBInstance> found = new ArrayList<>();
        for (int i = 0; i < identifiers.size(); i += CHUNK) {
            List<String> chunk = identifiers.subList(i, Math.min(i + CHUNK, identifiers.size()));
            String marker = null;
            do {
                DescribeDbInstancesResponse page = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                        .filters(Filter.builder().name("db-instance-id").values(chunk).build()).marker(marker).build());
                found.addAll(page.dbInstances());
                marker = page.marker();
            } while (marker != null && !marker.isBlank());
        }
        return found;
    }

    private List<DBCluster> describeClusters(RdsClient rds, List<String> identifiers) {
        List<DBCluster> found = new ArrayList<>();
        for (int i = 0; i < identifiers.size(); i += CHUNK) {
            List<String> chunk = identifiers.subList(i, Math.min(i + CHUNK, identifiers.size()));
            String marker = null;
            do {
                DescribeDbClustersResponse page = rds.describeDBClusters(DescribeDbClustersRequest.builder()
                        .filters(Filter.builder().name("db-cluster-id").values(chunk).build()).marker(marker).build());
                found.addAll(page.dbClusters());
                marker = page.marker();
            } while (marker != null && !marker.isBlank());
        }
        return found;
    }

    private RdsClient client(CloudTarget target) {
        String region = target.region() != null && !target.region().isBlank() ? target.region() : defaultRegion;
        return awsClientFactory.rds(target.accountRef(), region);
    }
}
