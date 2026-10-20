package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.VmDTO;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a VM listing adds to each VmDTO beyond the VM row itself: the private IP and the instance
 * type from the inventory snapshot (U05-T02: the "Size" column, an RDS instance's DB class), when
 * the VM entered its current status (U05-T01: "Running since 14:10") and its average CPU over the
 * last 24 hours (U07-T01). One inventory, one history and one metrics query, however many VMs are
 * listed.
 */
@Service
public class VmListDetailsService {

    /**
     * Extra facts about one listed VM. privateIp and instanceType are null until the inventory sync
     * has read the VM; stateSince is null when no history row matches its status.
     */
    public record Details(String privateIp, String instanceType, Timestamp stateSince, BigDecimal cpuAvg24h) {
        static final Details NONE = new Details(null, null, null, null);
    }

    private final VmInventorySnapshotRepository inventoryRepository;
    private final VmStateHistoryRepository historyRepository;
    private final VmMetricSampleRepository metricRepository;

    public VmListDetailsService(VmInventorySnapshotRepository inventoryRepository, VmStateHistoryRepository historyRepository,
                                VmMetricSampleRepository metricRepository) {
        this.inventoryRepository = inventoryRepository;
        this.historyRepository = historyRepository;
        this.metricRepository = metricRepository;
    }

    /**
     * Details for the given VMs, keyed by vmId. stateSince is the latest moment the VM entered the
     * status it has now; it stays null when there is no such row (registered while already in that
     * state, or the row was removed by data retention) rather than guessing from other times.
     */
    @Transactional(readOnly = true)
    public Map<String, Details> detailsFor(Collection<Vm> vms) {
        if (vms == null || vms.isEmpty()) {
            return Map.of();
        }
        List<String> vmIds = vms.stream().map(Vm::getVmId).distinct().toList();
        Map<String, VmInventorySnapshot> snapshots = new HashMap<>();
        for (VmInventorySnapshot snapshot : inventoryRepository.findByVmVmIdIn(vmIds)) {
            snapshots.put(snapshot.getVm().getVmId(), snapshot);
        }

        Map<String, Map<VmStatus, Timestamp>> enteredAt = new HashMap<>();
        for (Object[] row : historyRepository.findLastEnteredAtByStatus(vmIds)) {
            enteredAt.computeIfAbsent((String) row[0], id -> new HashMap<>()).put((VmStatus) row[1], (Timestamp) row[2]);
        }

        Map<String, BigDecimal> cpu = new HashMap<>();
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
        for (VmMetricSampleRepository.VmCpuAverage row : metricRepository.avgCpuByVmIdsSince(vmIds, since)) {
            if (row.getAvgCpu() != null) {
                cpu.put(row.getVmId(), BigDecimal.valueOf(row.getAvgCpu()).setScale(1, RoundingMode.HALF_UP));
            }
        }

        Map<String, Details> details = new HashMap<>();
        for (Vm vm : vms) {
            Map<VmStatus, Timestamp> byStatus = enteredAt.getOrDefault(vm.getVmId(), Map.of());
            VmInventorySnapshot snapshot = snapshots.get(vm.getVmId());
            details.put(vm.getVmId(), new Details(
                    snapshot != null ? snapshot.getPrivateIp() : null,
                    snapshot != null ? snapshot.getInstanceType() : null,
                    byStatus.get(vm.getStatus()),
                    cpu.get(vm.getVmId())));
        }
        return details;
    }

    /** One VM as a DTO with its details. */
    @Transactional(readOnly = true)
    public VmDTO toDto(Vm vm) {
        return apply(VmDTO.fromEntity(vm), detailsFor(List.of(vm)));
    }

    /** Copy the details for dto's VM (if any) onto it. */
    public static VmDTO apply(VmDTO dto, Map<String, Details> details) {
        Details d = details.getOrDefault(dto.getVmId(), Details.NONE);
        dto.setPrivateIp(d.privateIp());
        dto.setInstanceType(d.instanceType());
        dto.setStateSince(d.stateSince());
        dto.setCpuAvg24h(d.cpuAvg24h());
        return dto;
    }
}
