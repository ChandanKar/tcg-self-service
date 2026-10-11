package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostPreviewDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * What a start would cost (E25-T01): for the VMs in scope (environment, group or one VM, only
 * groups the user may see) that are not already running, the hourly rate from the pricing
 * reference (EKS node groups: desired nodes x node rate) times the hours. Hours default to the
 * environment's lease default when leases apply to it, else cost.preview.default-hours, and are
 * clamped to 1..cost.preview.max-hours. Prices are USD.
 */
@Service
public class OperationCostPreviewService {

    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final PricingReferenceService pricingReferenceService;
    private final EstimatedCostProvider estimatedCostProvider;
    private final SecurityService securityService;
    private final ObjectProvider<LeaseService> leaseService;

    @Value("${cost.preview.default-hours:8}")
    private int defaultHours = 8;

    @Value("${cost.preview.max-hours:720}")
    private int maxHours = 720;

    public OperationCostPreviewService(EnvironmentRepository environmentRepository, VmGroupRepository groupRepository,
                                       VmRepository vmRepository, VmInventorySnapshotRepository inventoryRepository,
                                       PricingReferenceService pricingReferenceService, EstimatedCostProvider estimatedCostProvider,
                                       SecurityService securityService, ObjectProvider<LeaseService> leaseService) {
        this.environmentRepository = environmentRepository;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
        this.inventoryRepository = inventoryRepository;
        this.pricingReferenceService = pricingReferenceService;
        this.estimatedCostProvider = estimatedCostProvider;
        this.securityService = securityService;
        this.leaseService = leaseService;
    }

    @Transactional(readOnly = true)
    public CostPreviewDTO preview(String environmentId, String groupId, String vmId, Integer hours) {
        return preview(environmentId, groupId, vmId, hours, false);
    }

    /**
     * extension = true prices keeping the RUNNING VMs running for the extra hours (a lease
     * extension, E25-T02); otherwise it prices starting the ones that are not running.
     */
    @Transactional(readOnly = true)
    public CostPreviewDTO preview(String environmentId, String groupId, String vmId, Integer hours, boolean extension) {
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        int effectiveHours = hours(environment, hours);

        Set<String> visibleGroups = new HashSet<>(securityService.getVisibleGroupIds(environmentId));
        List<Vm> inScope = scope(environmentId, groupId, vmId).stream()
                .filter(vm -> !Boolean.FALSE.equals(vm.getIsActive()))
                .filter(vm -> vm.getGroup() != null && visibleGroups.contains(vm.getGroup().getGroupId()))
                .toList();
        if (vmId != null && !vmId.isBlank() && inScope.isEmpty()) {
            throw new ResourceNotFoundException("Vm", vmId);
        }
        if (extension) {
            List<Vm> running = inScope.stream().filter(vm -> vm.getStatus() == VmStatus.RUNNING).toList();
            return price(running, effectiveHours, running.size());
        }
        List<Vm> toStart = inScope.stream().filter(vm -> vm.getStatus() != VmStatus.RUNNING).toList();
        int running = inScope.size() - toStart.size();
        return price(toStart, effectiveHours, running);
    }

    /** Hours for an extension or a start; also used by approvals (E25-T03). */
    public int hours(Environment environment, Integer requested) {
        int value;
        if (requested != null) {
            value = requested;
        } else {
            LeaseService leases = leaseService.getIfAvailable();
            value = leases != null && leases.isEnabledFor(environment)
                    ? (int) Math.ceil(leases.effectiveDefault(environment) / 60.0)
                    : defaultHours;
        }
        return Math.max(1, Math.min(maxHours, value));
    }

    /** Prices the given VMs for the hours (no scoping or visibility checks). */
    public CostPreviewDTO price(List<Vm> vms, int hours, int alreadyRunning) {
        Map<String, String> instanceTypes = new HashMap<>();
        if (!vms.isEmpty()) {
            inventoryRepository.findInstanceTypesByVmIds(vms.stream().map(Vm::getVmId).toList())
                    .forEach(p -> instanceTypes.put(p.getVmId(), p.getInstanceType()));
        }
        BigDecimal hourly = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int unknown = 0;
        boolean approximate = false;
        List<CostPreviewDTO.VmLine> lines = new ArrayList<>();
        for (Vm vm : vms) {
            boolean eks = vm.getProvider() == CloudProvider.AWS_EKS;
            EstimatedCostProvider.EksNodeInfo nodes = eks ? estimatedCostProvider.resolveEksNodeInfo(vm) : null;
            String type = eks ? nodes.instanceType() : instanceTypes.get(vm.getVmId());
            int nodeCount = eks ? nodes.nodeCount() : 1;
            PricingReferenceService.PriceLookupResult rate = type == null
                    ? new PricingReferenceService.PriceLookupResult(false, null)
                    : pricingReferenceService.lookupHourlyRate(eks ? "AWS" : vm.getProvider().name(), type, vm.getRegion());
            String name = vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName();
            if (rate.priceKnown() && rate.hourlyRate() != null) {
                BigDecimal vmRate = rate.hourlyRate().multiply(BigDecimal.valueOf(nodeCount));
                BigDecimal vmCost = vmRate.multiply(BigDecimal.valueOf(hours)).setScale(2, RoundingMode.HALF_UP);
                hourly = hourly.add(vmRate);
                total = total.add(vmCost);
                approximate |= rate.approximate();
                lines.add(new CostPreviewDTO.VmLine(vm.getVmId(), name, type, nodeCount, vmRate.setScale(4, RoundingMode.HALF_UP),
                        vmCost, true, rate.approximate()));
            } else {
                unknown++;
                lines.add(new CostPreviewDTO.VmLine(vm.getVmId(), name, type, nodeCount, null, BigDecimal.ZERO.setScale(2), false, false));
            }
        }
        return new CostPreviewDTO(hours, hourly.setScale(2, RoundingMode.HALF_UP), total.setScale(2, RoundingMode.HALF_UP), "USD",
                lines, unknown, alreadyRunning, approximate);
    }

    private List<Vm> scope(String environmentId, String groupId, String vmId) {
        if (vmId != null && !vmId.isBlank()) {
            Vm vm = vmRepository.findById(vmId).orElseThrow(() -> new ResourceNotFoundException("Vm", vmId));
            if (vm.getGroup() == null || vm.getGroup().getEnvironment() == null
                    || !environmentId.equals(vm.getGroup().getEnvironment().getEnvironmentId())) {
                throw new ResourceNotFoundException("Vm", vmId);
            }
            return List.of(vm);
        }
        if (groupId != null && !groupId.isBlank()) {
            VmGroup group = groupRepository.findById(groupId).orElseThrow(() -> new ResourceNotFoundException("VmGroup", groupId));
            if (group.getEnvironment() == null || !environmentId.equals(group.getEnvironment().getEnvironmentId())) {
                throw new ResourceNotFoundException("VmGroup", groupId);
            }
            return vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(groupId);
        }
        return vmRepository.findByEnvironmentIdFetchGroupAndEnvironment(environmentId);
    }
}
