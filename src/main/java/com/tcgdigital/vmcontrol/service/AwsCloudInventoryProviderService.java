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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AwsCloudInventoryProviderService implements CloudInventoryProviderService {

    private static final Logger log = LoggerFactory.getLogger(AwsCloudInventoryProviderService.class);

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion;

    private final Map<String, Ec2Client> clientCache = new ConcurrentHashMap<>();

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
    public Map<String, VmInventoryData> fetchInventory(List<String> providerVmIds, String region) {
        if (!isAvailable() || providerVmIds == null || providerVmIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, VmInventoryData> result = new HashMap<>();
        try {
            Ec2Client ec2 = getEc2Client(region);
            List<Instance> instances = describeInstances(ec2, providerVmIds);
            Set<String> volumeIds = instances.stream()
                    .flatMap(instance -> instance.blockDeviceMappings().stream())
                    .map(mapping -> mapping.ebs() != null ? mapping.ebs().volumeId() : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<String, Volume> volumes = describeVolumes(ec2, new ArrayList<>(volumeIds));
            Map<String, InstanceTypeInfo> typeInfo = describeInstanceTypes(ec2, instances);

            for (Instance instance : instances) {
                VmInventoryData data = new VmInventoryData();
                data.setProviderVmId(instance.instanceId());
                data.setInstanceType(instance.instanceTypeAsString());
                data.setArchitecture(instance.architectureAsString());
                data.setPrivateIp(instance.privateIpAddress());
                data.setPublicIp(instance.publicIpAddress());
                data.setAvailabilityZone(instance.placement() != null ? instance.placement().availabilityZone() : null);
                data.setLaunchTime(instance.launchTime() != null ? Timestamp.from(instance.launchTime()) : null);

                InstanceTypeInfo info = typeInfo.get(instance.instanceTypeAsString());
                if (info != null) {
                    if (info.vCpuInfo() != null) data.setVcpuCount(info.vCpuInfo().defaultVCpus());
                    if (info.memoryInfo() != null) data.setMemoryMib(Math.toIntExact(info.memoryInfo().sizeInMiB()));
                }

                List<VmVolumeData> volumeData = new ArrayList<>();
                for (InstanceBlockDeviceMapping mapping : instance.blockDeviceMappings()) {
                    if (mapping.ebs() == null || mapping.ebs().volumeId() == null) continue;
                    Volume volume = volumes.get(mapping.ebs().volumeId());
                    VmVolumeData vd = new VmVolumeData();
                    vd.setVolumeId(mapping.ebs().volumeId());
                    vd.setDeviceName(mapping.deviceName());
                    vd.setDeleteOnTermination(mapping.ebs().deleteOnTermination());
                    if (volume != null) {
                        vd.setVolumeType(volume.volumeTypeAsString());
                        vd.setSizeGib(volume.size());
                        vd.setIops(volume.iops());
                        vd.setThroughputMbps(volume.throughput());
                        vd.setEncrypted(volume.encrypted());
                    }
                    volumeData.add(vd);
                }
                data.setVolumes(volumeData);
                result.put(instance.instanceId(), data);
            }
        } catch (Exception e) {
            log.error("Failed to fetch AWS inventory for {} VM(s) in {}: {}", providerVmIds.size(), region, e.getMessage());
        }
        return result;
    }

    private List<Instance> describeInstances(Ec2Client ec2, List<String> instanceIds) {
        List<Instance> instances = new ArrayList<>();
        for (int i = 0; i < instanceIds.size(); i += 100) {
            List<String> chunk = instanceIds.subList(i, Math.min(i + 100, instanceIds.size()));
            DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .instanceIds(chunk)
                    .build());
            for (Reservation reservation : response.reservations()) {
                instances.addAll(reservation.instances());
            }
        }
        return instances;
    }

    private Map<String, Volume> describeVolumes(Ec2Client ec2, List<String> volumeIds) {
        if (volumeIds.isEmpty()) return Collections.emptyMap();
        Map<String, Volume> volumes = new HashMap<>();
        for (int i = 0; i < volumeIds.size(); i += 100) {
            List<String> chunk = volumeIds.subList(i, Math.min(i + 100, volumeIds.size()));
            DescribeVolumesResponse response = ec2.describeVolumes(DescribeVolumesRequest.builder()
                    .volumeIds(chunk)
                    .build());
            for (Volume volume : response.volumes()) {
                volumes.put(volume.volumeId(), volume);
            }
        }
        return volumes;
    }

    private Map<String, InstanceTypeInfo> describeInstanceTypes(Ec2Client ec2, List<Instance> instances) {
        List<String> types = instances.stream()
                .map(Instance::instanceTypeAsString)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (types.isEmpty()) return Collections.emptyMap();

        Map<String, InstanceTypeInfo> result = new HashMap<>();
        for (int i = 0; i < types.size(); i += 100) {
            List<String> chunk = types.subList(i, Math.min(i + 100, types.size()));
            DescribeInstanceTypesResponse response = ec2.describeInstanceTypes(DescribeInstanceTypesRequest.builder()
                    .instanceTypesWithStrings(chunk)
                    .build());
            for (InstanceTypeInfo info : response.instanceTypes()) {
                result.put(info.instanceTypeAsString(), info);
            }
        }
        return result;
    }

    private Ec2Client getEc2Client(String region) {
        String effectiveRegion = region != null && !region.isBlank() ? region : defaultRegion;
        return clientCache.computeIfAbsent(effectiveRegion, r -> Ec2Client.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .build())
                .build());
    }
}
