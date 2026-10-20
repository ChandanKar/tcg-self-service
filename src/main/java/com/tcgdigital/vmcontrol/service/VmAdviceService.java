package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.VmAdviceDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One VM's cost this month and its rightsizing recommendation (U08-T06), from what the platform
 * already has: the cost estimate the environment's cost card uses and the stored rightsizing
 * candidates (rules plus the captured Compute Optimizer findings). No cloud calls. Callers check
 * that the user may view the VM; the admin-only rightsizing list stays admin-only.
 */
@Service
public class VmAdviceService {

    private final VmRepository vmRepository;
    private final CostDataProvider costDataProvider;
    private final CostEstimationService costEstimationService;
    private Clock clock = Clock.systemDefaultZone();

    public VmAdviceService(VmRepository vmRepository, CostDataProvider costDataProvider,
                           CostEstimationService costEstimationService) {
        this.vmRepository = vmRepository;
        this.costDataProvider = costDataProvider;
        this.costEstimationService = costEstimationService;
    }

    @Transactional(readOnly = true)
    public VmAdviceDTO advice(String vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(() -> new ResourceNotFoundException("Vm", vmId));
        Instant now = clock.instant();
        Instant monthStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(clock.getZone()).toInstant();
        CostDataProvider.VmCostEstimate estimate = costDataProvider
                .estimateCosts(List.of(vm), Timestamp.from(monthStart), Timestamp.from(now)).get(vmId);
        boolean known = estimate != null && estimate.costKnown();
        VmAdviceDTO.Rightsizing rightsizing = costEstimationService.findCandidate(vmId)
                .map(VmAdviceService::rightsizing)
                .orElse(null);
        return new VmAdviceDTO(
                known ? money(estimate.cost()) : null,
                known && estimate.hourlyRate() != null ? estimate.hourlyRate().setScale(4, RoundingMode.HALF_UP) : null,
                known && estimate.priceApproximate(),
                vm.getPurchaseOption() == null ? null : vm.getPurchaseOption().name(),
                rightsizing);
    }

    /** The candidate as advice; null when it suggests no other size. */
    static VmAdviceDTO.Rightsizing rightsizing(RightsizingCandidateDTO c) {
        if (c.suggestedInstanceType() == null || c.suggestedInstanceType().equals(c.currentInstanceType())) {
            return null;
        }
        return new VmAdviceDTO.Rightsizing(c.currentInstanceType(), c.suggestedInstanceType(), c.direction(),
                c.estimatedMonthlySavings() == null ? null : money(c.estimatedMonthlySavings()),
                c.peakCpuUtilization(), c.peakMemoryUtilization(), c.source(), "AGREE".equals(c.agreement()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
