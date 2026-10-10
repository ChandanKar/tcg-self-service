package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EstimatedCostProvider.class);

    private final PricingReferenceService pricingReferenceService;
    private final VmCostCalculator vmCostCalculator;
    private final VmVolumeSnapshotRepository vmVolumeSnapshotRepository;
    private final VmStateHistoryRepository vmStateHistoryRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;
    private final ObjectMapper objectMapper;

    public EstimatedCostProvider(PricingReferenceService pricingReferenceService,
                                  VmCostCalculator vmCostCalculator,
                                  VmVolumeSnapshotRepository vmVolumeSnapshotRepository,
                                  VmStateHistoryRepository vmStateHistoryRepository,
                                  VmInventorySnapshotRepository vmInventorySnapshotRepository,
                                  ObjectMapper objectMapper) {
        this.pricingReferenceService = pricingReferenceService;
        this.vmCostCalculator = vmCostCalculator;
        this.vmVolumeSnapshotRepository = vmVolumeSnapshotRepository;
        this.vmStateHistoryRepository = vmStateHistoryRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
        this.objectMapper = objectMapper;
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

        for (Vm vm : vms) {
            String vmId = vm.getVmId();
            List<VmStateHistory> transitions = transitionsByVmId.getOrDefault(vmId, List.of());
            long storageGib = storageByVmId.getOrDefault(vmId, 0L);
            // Storage only for the part of the window the VM existed (E08-T01).
            BigDecimal storageDays = vmCostCalculator.storageWindowDays(vm.getCreatedAt(), windowStart, windowEnd);

            VmStatus seedStatus = resolveSeedStatus(vm, transitions, windowStart);
            BigDecimal runtimeHours = vmCostCalculator.computeRuntimeHours(vm, seedStatus, transitions, windowStart, windowEnd);

            boolean isEks = vm.getProvider() == CloudProvider.AWS_EKS;
            EksNodeInfo eksNodeInfo = isEks ? resolveEksNodeInfo(vm) : null;
            String pricingProvider = isEks ? "AWS" : vm.getProvider().name();
            String instanceType = isEks ? eksNodeInfo.instanceType() : instanceTypeByVmId.get(vmId);
            int nodeCount = isEks ? eksNodeInfo.nodeCount() : 1;

            PricingReferenceService.PriceLookupResult rate =
                    pricingReferenceService.lookupHourlyRate(pricingProvider, instanceType, vm.getRegion());

            if (rate.priceKnown()) {
                BigDecimal effectiveHourlyRate = rate.hourlyRate().multiply(BigDecimal.valueOf(nodeCount));
                BigDecimal cost = vmCostCalculator.estimateCost(effectiveHourlyRate, runtimeHours, storageGib, storageGbMonthRate, storageDays);
                result.put(vmId, new VmCostEstimate(true, effectiveHourlyRate, runtimeHours, storageGib, cost));
            } else {
                BigDecimal proratedStorageRate = storageGbMonthRate.multiply(storageDays)
                        .divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP);
                BigDecimal storageOnlyCost = proratedStorageRate.multiply(BigDecimal.valueOf(storageGib))
                        .setScale(2, RoundingMode.HALF_UP);
                result.put(vmId, new VmCostEstimate(false, null, runtimeHours, storageGib, storageOnlyCost));
            }
        }

        return result;
    }

    /**
     * EKS node groups are represented as a single Vm per group (not per node), so there's no
     * VmInventorySnapshot to read — instanceType and node count are captured instead in
     * Vm.metadata by EksSyncService directly from the EKS DescribeNodegroup response. Nodes run
     * on plain EC2 hardware, so the standard AWS pricing reference applies per node.
     */
    private EksNodeInfo resolveEksNodeInfo(Vm vm) {
        String metadata = vm.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return new EksNodeInfo(null, 1);
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            String instanceType = node.hasNonNull("instanceType") ? node.get("instanceType").asText() : null;
            int desiredSize = node.hasNonNull("desiredSize") ? node.get("desiredSize").asInt() : 1;
            return new EksNodeInfo(instanceType, Math.max(desiredSize, 1));
        } catch (Exception e) {
            log.debug("Could not parse EKS node group metadata for VM {}: {}", vm.getVmId(), e.getMessage());
            return new EksNodeInfo(null, 1);
        }
    }

    private record EksNodeInfo(String instanceType, int nodeCount) {}

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
