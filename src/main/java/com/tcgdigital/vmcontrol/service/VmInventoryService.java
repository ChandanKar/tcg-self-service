package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.VmInventoryDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class VmInventoryService {

    private static final Logger log = LoggerFactory.getLogger(VmInventoryService.class);

    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final VmVolumeSnapshotRepository volumeRepository;
    private final CloudInventoryProviderFactory providerFactory;

    public VmInventoryService(VmRepository vmRepository,
                              VmInventorySnapshotRepository inventoryRepository,
                              VmVolumeSnapshotRepository volumeRepository,
                              CloudInventoryProviderFactory providerFactory) {
        this.vmRepository = vmRepository;
        this.inventoryRepository = inventoryRepository;
        this.volumeRepository = volumeRepository;
        this.providerFactory = providerFactory;
    }

    @Transactional(readOnly = true)
    public VmInventoryDTO getInventory(String environmentId, String vmId) {
        validateVmInEnvironment(environmentId, vmId);
        VmInventorySnapshot inventory = inventoryRepository.findByVmVmId(vmId).orElse(null);
        List<VmVolumeSnapshot> volumes = volumeRepository.findByVmVmIdOrderByDeviceNameAsc(vmId);
        return VmInventoryDTO.from(inventory, volumes);
    }

    @Transactional
    public int syncAllInventory() {
        return syncInventory(vmRepository.findByIsActiveTrue());
    }

    @Transactional
    public int syncEnvironmentInventory(String environmentId) {
        return syncInventory(vmRepository.findByEnvironmentId(environmentId));
    }

    @Transactional
    public VmInventoryDTO refreshVmInventory(String environmentId, String vmId) {
        Vm vm = validateVmInEnvironment(environmentId, vmId);
        syncInventory(List.of(vm));
        return getInventory(environmentId, vmId);
    }

    private int syncInventory(List<Vm> vms) {
        Map<String, List<Vm>> groups = vms.stream()
                .filter(vm -> vm.getProviderVmId() != null && !vm.getProviderVmId().isBlank())
                .collect(Collectors.groupingBy(vm -> vm.getProvider().name() + ":" + vm.getRegion()));

        int updated = 0;
        for (Map.Entry<String, List<Vm>> entry : groups.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            CloudProvider provider = CloudProvider.valueOf(parts[0]);
            String region = parts.length > 1 ? parts[1] : null;

            CloudInventoryProviderService service = providerFactory.getService(provider).orElse(null);
            if (service == null || !service.isAvailable()) {
                log.debug("Inventory provider {} is unavailable; skipping {} VM(s)", provider, entry.getValue().size());
                continue;
            }

            List<String> ids = entry.getValue().stream().map(Vm::getProviderVmId).toList();
            Map<String, CloudInventoryProviderService.VmInventoryData> inventory = service.fetchInventory(ids, region);
            for (Vm vm : entry.getValue()) {
                CloudInventoryProviderService.VmInventoryData data = inventory.get(vm.getProviderVmId());
                if (data == null) continue;
                saveInventory(vm, data);
                updated++;
            }
        }
        log.info("VM inventory sync completed: {} VM(s) updated", updated);
        return updated;
    }

    private void saveInventory(Vm vm, CloudInventoryProviderService.VmInventoryData data) {
        Timestamp now = Timestamp.from(Instant.now());
        VmInventorySnapshot snapshot = inventoryRepository.findByVmVmId(vm.getVmId())
                .orElseGet(VmInventorySnapshot::new);
        snapshot.setVm(vm);
        snapshot.setProvider(vm.getProvider());
        snapshot.setProviderVmId(vm.getProviderVmId());
        snapshot.setInstanceType(data.getInstanceType());
        snapshot.setVcpuCount(data.getVcpuCount());
        snapshot.setMemoryMib(data.getMemoryMib());
        snapshot.setArchitecture(data.getArchitecture());
        snapshot.setPrivateIp(data.getPrivateIp());
        snapshot.setPublicIp(data.getPublicIp());
        snapshot.setAvailabilityZone(data.getAvailabilityZone());
        snapshot.setLaunchTime(data.getLaunchTime());
        snapshot.setTotalStorageGib(data.getTotalStorageGib());
        snapshot.setLastRefreshedAt(now);
        inventoryRepository.save(snapshot);

        List<String> currentVolumeIds = data.getVolumes().stream()
                .map(CloudInventoryProviderService.VmVolumeData::getVolumeId)
                .filter(Objects::nonNull)
                .toList();
        if (currentVolumeIds.isEmpty()) {
            volumeRepository.deleteByVmVmId(vm.getVmId());
        } else {
            volumeRepository.deleteByVmVmIdAndVolumeIdNotIn(vm.getVmId(), currentVolumeIds);
        }

        for (CloudInventoryProviderService.VmVolumeData volumeData : data.getVolumes()) {
            if (volumeData.getVolumeId() == null) continue;
            VmVolumeSnapshot volume = volumeRepository.findByVmVmIdAndVolumeId(vm.getVmId(), volumeData.getVolumeId())
                    .orElseGet(VmVolumeSnapshot::new);
            volume.setVm(vm);
            volume.setVolumeId(volumeData.getVolumeId());
            volume.setDeviceName(volumeData.getDeviceName());
            volume.setVolumeType(volumeData.getVolumeType());
            volume.setSizeGib(volumeData.getSizeGib());
            volume.setIops(volumeData.getIops());
            volume.setThroughputMbps(volumeData.getThroughputMbps());
            volume.setEncrypted(volumeData.getEncrypted());
            volume.setDeleteOnTermination(volumeData.getDeleteOnTermination());
            volume.setLastRefreshedAt(now);
            volumeRepository.save(volume);
        }
    }

    private Vm validateVmInEnvironment(String environmentId, String vmId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("Vm", vmId));
        String actualEnvironmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        if (!actualEnvironmentId.equals(environmentId)) {
            throw new ResourceNotFoundException("Vm not found in environment: " + vmId);
        }
        return vm;
    }
}
