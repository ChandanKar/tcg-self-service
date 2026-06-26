package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;

import java.sql.Timestamp;
import java.util.List;

public class VmInventoryDTO {
    private String vmId;
    private String providerVmId;
    private String instanceType;
    private Integer vcpuCount;
    private Integer memoryMib;
    private String architecture;
    private String privateIp;
    private String publicIp;
    private String availabilityZone;
    private Timestamp launchTime;
    private Integer totalStorageGib;
    private Timestamp lastRefreshedAt;
    private List<VolumeDTO> volumes;

    public static VmInventoryDTO from(VmInventorySnapshot snapshot, List<VmVolumeSnapshot> volumes) {
        VmInventoryDTO dto = new VmInventoryDTO();
        if (snapshot != null) {
            dto.setVmId(snapshot.getVm().getVmId());
            dto.setProviderVmId(snapshot.getProviderVmId());
            dto.setInstanceType(snapshot.getInstanceType());
            dto.setVcpuCount(snapshot.getVcpuCount());
            dto.setMemoryMib(snapshot.getMemoryMib());
            dto.setArchitecture(snapshot.getArchitecture());
            dto.setPrivateIp(snapshot.getPrivateIp());
            dto.setPublicIp(snapshot.getPublicIp());
            dto.setAvailabilityZone(snapshot.getAvailabilityZone());
            dto.setLaunchTime(snapshot.getLaunchTime());
            dto.setTotalStorageGib(snapshot.getTotalStorageGib());
            dto.setLastRefreshedAt(snapshot.getLastRefreshedAt());
        }
        dto.setVolumes(volumes.stream().map(VolumeDTO::from).toList());
        return dto;
    }

    public static class VolumeDTO {
        private String volumeId;
        private String deviceName;
        private String volumeType;
        private Integer sizeGib;
        private Integer iops;
        private Integer throughputMbps;
        private Boolean encrypted;
        private Boolean deleteOnTermination;

        public static VolumeDTO from(VmVolumeSnapshot snapshot) {
            VolumeDTO dto = new VolumeDTO();
            dto.setVolumeId(snapshot.getVolumeId());
            dto.setDeviceName(snapshot.getDeviceName());
            dto.setVolumeType(snapshot.getVolumeType());
            dto.setSizeGib(snapshot.getSizeGib());
            dto.setIops(snapshot.getIops());
            dto.setThroughputMbps(snapshot.getThroughputMbps());
            dto.setEncrypted(snapshot.getEncrypted());
            dto.setDeleteOnTermination(snapshot.getDeleteOnTermination());
            return dto;
        }

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

    public String getVmId() { return vmId; }
    public void setVmId(String vmId) { this.vmId = vmId; }
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
    public Integer getTotalStorageGib() { return totalStorageGib; }
    public void setTotalStorageGib(Integer totalStorageGib) { this.totalStorageGib = totalStorageGib; }
    public Timestamp getLastRefreshedAt() { return lastRefreshedAt; }
    public void setLastRefreshedAt(Timestamp lastRefreshedAt) { this.lastRefreshedAt = lastRefreshedAt; }
    public List<VolumeDTO> getVolumes() { return volumes; }
    public void setVolumes(List<VolumeDTO> volumes) { this.volumes = volumes; }
}
