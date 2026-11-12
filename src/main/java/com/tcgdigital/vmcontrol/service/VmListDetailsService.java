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
    public record Details(String privateIp, String instanceType, Timestamp stateSince, BigDecimal cpuAvg24h,
                          String startedBy, String startedByUserId, Timestamp startedAt) {
        static final Details NONE = new Details(null, null, null, null, null, null, null);

        public Details(String privateIp, String instanceType, Timestamp stateSince, BigDecimal cpuAvg24h) {
            this(privateIp, instanceType, stateSince, cpuAvg24h, null, null, null);
        }
    }

    /** How far a start may be from the moment the VM was seen running and still count as its start. */
    static final Duration START_MATCH = Duration.ofMinutes(20);
    private static final Duration START_LOOKBACK = Duration.ofDays(45);

    private com.tcgdigital.vmcontrol.repository.OperationDetailRepository detailRepository;
    private com.tcgdigital.vmcontrol.repository.UserRepository userRepository;

    /** U22-T10: who started each running VM; optional so the listing still works without them. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStartedByLookup(com.tcgdigital.vmcontrol.repository.OperationDetailRepository detailRepository,
                            com.tcgdigital.vmcontrol.repository.UserRepository userRepository) {
        this.detailRepository = detailRepository;
        this.userRepository = userRepository;
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

        Map<String, Started> started = startedBy(vms, enteredAt);

        Map<String, Details> details = new HashMap<>();
        for (Vm vm : vms) {
            Map<VmStatus, Timestamp> byStatus = enteredAt.getOrDefault(vm.getVmId(), Map.of());
            VmInventorySnapshot snapshot = snapshots.get(vm.getVmId());
            Started who = started.get(vm.getVmId());
            details.put(vm.getVmId(), new Details(
                    snapshot != null ? snapshot.getPrivateIp() : null,
                    snapshot != null ? snapshot.getInstanceType() : null,
                    byStatus.get(vm.getStatus()),
                    cpu.get(vm.getVmId()),
                    who == null ? null : who.who(), who == null ? null : who.userId(), who == null ? null : who.at()));
        }
        return details;
    }

    private record Started(String who, String userId, Timestamp at) {
    }

    /**
     * U22-T10 (staging review: stopping someone else's VM gave no warning; "started by" was on page 2 of
     * History). For each running VM: the completed start that brought it up (within START_MATCH of the
     * moment it was seen running): a person's name, "the schedule <name>", or "outside the platform" when
     * no start of ours matches. One operations and one users query.
     */
    private Map<String, Started> startedBy(Collection<Vm> vms, Map<String, Map<VmStatus, Timestamp>> enteredAt) {
        Map<String, Started> out = new HashMap<>();
        if (detailRepository == null) {
            return out;
        }
        Map<String, Timestamp> runningSince = new HashMap<>();
        for (Vm vm : vms) {
            if (vm.getStatus() == VmStatus.RUNNING) {
                Timestamp since = enteredAt.getOrDefault(vm.getVmId(), Map.of()).get(VmStatus.RUNNING);
                if (since != null) runningSince.put(vm.getVmId(), since);
            }
        }
        if (runningSince.isEmpty()) {
            return out;
        }
        Map<String, Object[]> match = new HashMap<>();
        for (Object[] row : detailRepository.findCompletedStartsSince(List.copyOf(runningSince.keySet()),
                Timestamp.from(Instant.now().minus(START_LOOKBACK)))) {
            String vmId = (String) row[0];
            Timestamp completed = (Timestamp) row[1];
            Timestamp since = runningSince.get(vmId);
            if (since != null && !match.containsKey(vmId) && completed != null
                    && Duration.between(completed.toInstant(), since.toInstant()).abs().compareTo(START_MATCH) <= 0) {
                match.put(vmId, row);
            }
        }
        Map<String, String> names = new HashMap<>();
        if (userRepository != null) {
            List<String> userIds = match.values().stream().map(r -> (String) r[2])
                    .filter(java.util.Objects::nonNull).distinct().toList();
            userRepository.findAllById(userIds).forEach(u -> names.put(u.getUserId(),
                    u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail()));
        }
        runningSince.forEach((vmId, since) -> {
            Object[] row = match.get(vmId);
            if (row == null) {
                out.put(vmId, new Started("outside the platform", null, null));
                return;
            }
            boolean schedule = "SCHEDULE".equals(String.valueOf(row[3])) && row[4] != null;
            String userId = (String) row[2];
            out.put(vmId, new Started(schedule ? "the schedule " + row[4] : names.getOrDefault(userId, "someone"),
                    schedule ? null : userId, (Timestamp) row[1]));
        });
        return out;
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
        dto.setStartedBy(d.startedBy());
        dto.setStartedByUserId(d.startedByUserId());
        dto.setStartedAt(d.startedAt());
        return dto;
    }
}
