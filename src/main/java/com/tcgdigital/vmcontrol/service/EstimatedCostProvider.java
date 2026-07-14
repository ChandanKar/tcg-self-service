package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cost source backed by the static pricing reference and this app's own runtime/storage data —
 * no external billing API. Active whenever {@code cost.provider} is unset or {@code estimated}.
 */
@Service
@ConditionalOnProperty(name = "cost.provider", havingValue = "estimated", matchIfMissing = true)
public class EstimatedCostProvider implements CostDataProvider {

    private final PricingReferenceService pricingReferenceService;
    private final VmCostCalculator vmCostCalculator;
    private final VmVolumeSnapshotRepository vmVolumeSnapshotRepository;
    private final VmStateHistoryRepository vmStateHistoryRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;

    public EstimatedCostProvider(PricingReferenceService pricingReferenceService,
                                  VmCostCalculator vmCostCalculator,
                                  VmVolumeSnapshotRepository vmVolumeSnapshotRepository,
                                  VmStateHistoryRepository vmStateHistoryRepository,
                                  VmInventorySnapshotRepository vmInventorySnapshotRepository) {
        this.pricingReferenceService = pricingReferenceService;
        this.vmCostCalculator = vmCostCalculator;
        this.vmVolumeSnapshotRepository = vmVolumeSnapshotRepository;
        this.vmStateHistoryRepository = vmStateHistoryRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
    }

    @Override
    public Map<String, VmCostEstimate> estimateCosts(List<Vm> vms, Timestamp windowStart, Timestamp windowEnd) {
        Map<String, VmCostEstimate> result = new LinkedHashMap<>();
        if (vms.isEmpty()) {
            return result;
        }

        List<String> vmIds = vms.stream().map(Vm::getVmId).toList();

        Map<String, Long> storageByVmId = new LinkedHashMap<>();
        for (VmVolumeSnapshotRepository.VolumeSizeTotal total : vmVolumeSnapshotRepository.sumSizeGibByVmIds(vmIds)) {
            storageByVmId.put(total.getVmId(), total.getTotalSizeGib());
        }

        Map<String, String> instanceTypeByVmId = new LinkedHashMap<>();
        for (VmInventorySnapshotRepository.InstanceTypeProjection projection
                : vmInventorySnapshotRepository.findInstanceTypesByVmIds(vmIds)) {
            instanceTypeByVmId.put(projection.getVmId(), projection.getInstanceType());
        }

        Map<String, List<VmStateHistory>> transitionsByVmId = new LinkedHashMap<>();
        for (VmStateHistory transition : vmStateHistoryRepository
                .findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(vmIds, windowStart, windowEnd)) {
            transitionsByVmId.computeIfAbsent(transition.getVm().getVmId(), k -> new ArrayList<>()).add(transition);
        }

        BigDecimal storageGbMonthRate = pricingReferenceService.getStorageGbMonthRate();
        BigDecimal windowDays = vmCostCalculator.windowDays(windowStart, windowEnd);

        for (Vm vm : vms) {
            String vmId = vm.getVmId();
            List<VmStateHistory> transitions = transitionsByVmId.getOrDefault(vmId, List.of());
            long storageGib = storageByVmId.getOrDefault(vmId, 0L);

            VmStatus seedStatus = resolveSeedStatus(vm, transitions, windowStart);
            BigDecimal runtimeHours = vmCostCalculator.computeRuntimeHours(vm, seedStatus, transitions, windowStart, windowEnd);

            String instanceType = instanceTypeByVmId.get(vmId);
            PricingReferenceService.PriceLookupResult rate =
                    pricingReferenceService.lookupHourlyRate(vm.getProvider().name(), instanceType, vm.getRegion());

            if (rate.priceKnown()) {
                BigDecimal cost = vmCostCalculator.estimateCost(rate.hourlyRate(), runtimeHours, storageGib, storageGbMonthRate, windowDays);
                result.put(vmId, new VmCostEstimate(true, rate.hourlyRate(), runtimeHours, storageGib, cost));
            } else {
                BigDecimal proratedStorageRate = storageGbMonthRate.multiply(windowDays)
                        .divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP);
                BigDecimal storageOnlyCost = proratedStorageRate.multiply(BigDecimal.valueOf(storageGib))
                        .setScale(2, RoundingMode.HALF_UP);
                result.put(vmId, new VmCostEstimate(false, null, runtimeHours, storageGib, storageOnlyCost));
            }
        }

        return result;
    }

    /**
     * The first in-window transition already carries its own previousStatus, so only VMs with
     * zero in-window transitions need the single-row lookback fallback; only if even that finds
     * nothing do we fall back to the VM's current status as a last-resort assumption.
     */
    private VmStatus resolveSeedStatus(Vm vm, List<VmStateHistory> transitionsInWindow, Timestamp windowStart) {
        if (!transitionsInWindow.isEmpty() && transitionsInWindow.get(0).getPreviousStatus() != null) {
            return transitionsInWindow.get(0).getPreviousStatus();
        }
        Optional<VmStateHistory> before = vmStateHistoryRepository
                .findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc(vm.getVmId(), windowStart);
        if (before.isPresent()) {
            return before.get().getNewStatus();
        }
        return vm.getStatus();
    }
}
