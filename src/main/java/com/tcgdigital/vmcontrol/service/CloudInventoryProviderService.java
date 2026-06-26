package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface CloudInventoryProviderService {

    CloudProvider getProvider();

    boolean isAvailable();

    Map<String, VmInventoryData> fetchInventory(List<String> providerVmIds, String region);

    class VmInventoryData {
        private String providerVmId;
        private String instanceType;
        private Integer vcpuCount;
        private Integer memoryMib;
        private String architecture;
        private String privateIp;
        private String publicIp;
        private String availabilityZone;
        private Timestamp launchTime;
        private List<VmVolumeData> volumes = List.of();

        public Integer getTotalStorageGib() {
            return volumes == null ? 0 : volumes.stream()
                    .map(VmVolumeData::getSizeGib)
                    .filter(size -> size != null)
                    .reduce(0, Integer::sum);
        }

        public String getProviderVmId() { return providerVmId; }
        public void setProviderVmId(String providerVmId) { this.providerVmId = providerVmId; }
        public String getInstanceType() { return instanceType; }
        public void setInstanceType(String instanceType) { this.instanceType = instanceType; }
        public Integer getVcpuCount() { return vcpuCount; }
        public void setVcpuCount(Integer vcpuCount) { this.vcpuCount = vcpuCount; }
        public Integer getMemoryMib() { return memoryMib; }
        public void setMemoryMib(Integer memoryMib) { this.memoryMib = memoryMib; }
        public String getArchitecture() { return architecture; }
        public void setArchitecture(String architecture) { this.architecture = architecture; }
        public String getPrivateIp() { return privateIp; }
        public void setPrivateIp(String privateIp) { this.privateIp = privateIp; }
        public String getPublicIp() { return publicIp; }
        public void setPublicIp(String publicIp) { this.publicIp = publicIp; }
        public String getAvailabilityZone() { return availabilityZone; }
        public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
        public Timestamp getLaunchTime() { return launchTime; }
        public void setLaunchTime(Timestamp launchTime) { this.launchTime = launchTime; }
        public List<VmVolumeData> getVolumes() { return volumes; }
        public void setVolumes(List<VmVolumeData> volumes) { this.volumes = volumes; }
    }

    class VmVolumeData {
        private String volumeId;
        private String deviceName;
        private String volumeType;
        private Integer sizeGib;
        private Integer iops;
        private Integer throughputMbps;
        private Boolean encrypted;
        private Boolean deleteOnTermination;

        public String getVolumeId() { return volumeId; }
        public void setVolumeId(String volumeId) { this.volumeId = volumeId; }
        public String getDeviceName() { return deviceName; }
        public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
        public String getVolumeType() { return volumeType; }
        public void setVolumeType(String volumeType) { this.volumeType = volumeType; }
        public Integer getSizeGib() { return sizeGib; }
        public void setSizeGib(Integer sizeGib) { this.sizeGib = sizeGib; }
        public Integer getIops() { return iops; }
        public void setIops(Integer iops) { this.iops = iops; }
        public Integer getThroughputMbps() { return throughputMbps; }
        public void setThroughputMbps(Integer throughputMbps) { this.throughputMbps = throughputMbps; }
        public Boolean getEncrypted() { return encrypted; }
        public void setEncrypted(Boolean encrypted) { this.encrypted = encrypted; }
        public Boolean getDeleteOnTermination() { return deleteOnTermination; }
        public void setDeleteOnTermination(Boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }
    }
}
