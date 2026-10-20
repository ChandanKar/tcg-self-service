package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.VmDTO;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a VM listing adds to each VmDTO beyond the VM row itself (U05-T01): the private IP from the
 * inventory snapshot and when the VM entered its current status ("Running since 14:10"). Both come
 * from one query each, however many VMs are listed.
 */
@Service
public class VmListDetailsService {

    /** Extra facts about one listed VM. stateSince is null when no history row matches its status. */
    public record Details(String privateIp, Timestamp stateSince) {
        static final Details NONE = new Details(null, null);
    }

    private final VmInventoryService inventoryService;
    private final VmStateHistoryRepository historyRepository;

    public VmListDetailsService(VmInventoryService inventoryService, VmStateHistoryRepository historyRepository) {
        this.inventoryService = inventoryService;
        this.historyRepository = historyRepository;
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
        Map<String, String> privateIps = inventoryService.getPrivateIpsByVmIds(vmIds);

        Map<String, Map<VmStatus, Timestamp>> enteredAt = new HashMap<>();
        for (Object[] row : historyRepository.findLastEnteredAtByStatus(vmIds)) {
            enteredAt.computeIfAbsent((String) row[0], id -> new HashMap<>()).put((VmStatus) row[1], (Timestamp) row[2]);
        }

        Map<String, Details> details = new HashMap<>();
        for (Vm vm : vms) {
            Map<VmStatus, Timestamp> byStatus = enteredAt.getOrDefault(vm.getVmId(), Map.of());
            details.put(vm.getVmId(), new Details(privateIps.get(vm.getVmId()), byStatus.get(vm.getStatus())));
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
        dto.setStateSince(d.stateSince());
        return dto;
    }
}
