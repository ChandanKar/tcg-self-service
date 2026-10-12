package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.SpotSummaryDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An environment's Spot summary for its page (E31-T08): anyone who can view the environment sees
 * it; whether they may change it is part of the answer. Not found while Spot is off, so the page
 * shows no panel.
 */
@Service
public class SpotSummaryService {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final EnvironmentRepository environmentRepository;
    private final VmRepository vmRepository;
    private final VmStateHistoryRepository stateHistoryRepository;
    private final CostDataProvider costDataProvider;
    private final SecurityService securityService;
    private final SpotPolicyService spotPolicy;
    private final SpotConversionService conversionService;

    public SpotSummaryService(EnvironmentRepository environmentRepository, VmRepository vmRepository,
                              VmStateHistoryRepository stateHistoryRepository, CostDataProvider costDataProvider,
                              SecurityService securityService, SpotPolicyService spotPolicy,
                              SpotConversionService conversionService) {
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.costDataProvider = costDataProvider;
        this.securityService = securityService;
        this.spotPolicy = spotPolicy;
        this.conversionService = conversionService;
    }

    public SpotSummaryDTO summary(String environmentId) {
        if (!spotPolicy.isEnabled()) {
            throw new ResourceNotFoundException("Spot is not enabled");
        }
        securityService.assertCanView(environmentId);
        Environment env = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        boolean canAdminister = securityService.canAdministerEnvironment(environmentId);
        boolean conversion = conversionService.conversionEnabled();
        boolean allowed = env.getSpotPolicy() == SpotPolicy.ALLOWED;

        List<Vm> vms = vmRepository.findByEnvironmentId(environmentId).stream()
                .filter(v -> v.getProvider() == CloudProvider.AWS || v.getProvider() == CloudProvider.AWS_EKS)
                .toList();
        Map<String, Vm> byId = vms.stream().collect(Collectors.toMap(Vm::getVmId, Function.identity(), (a, b) -> a));

        Instant now = Instant.now();
        BigDecimal savings = BigDecimal.ZERO;
        List<Vm> spotVms = vms.stream().filter(v -> v.getPurchaseOption() == PurchaseOption.SPOT).toList();
        if (!spotVms.isEmpty()) {
            for (CostDataProvider.VmCostEstimate e : costDataProvider.estimateCosts(spotVms,
                    Timestamp.from(now.minus(WINDOW)), Timestamp.from(now)).values()) {
                if (e.onDemandEquivalentCost() != null && e.cost() != null) {
                    savings = savings.add(e.onDemandEquivalentCost().subtract(e.cost()));
                }
            }
        }

        List<SpotSummaryDTO.SpotVm> rows = vms.stream().map(v -> {
            boolean eks = v.getProvider() == CloudProvider.AWS_EKS;
            Vm partner = v.getSpotPartnerVmId() == null ? null : byId.get(v.getSpotPartnerVmId());
            boolean convertible = canAdminister && conversion && allowed && eks && !v.isSpotExcluded()
                    && v.getPurchaseOption() == PurchaseOption.ON_DEMAND && !v.isStandby() && v.getSpotPartnerVmId() == null
                    && v.getStatus() == VmStatus.RUNNING;
            boolean revertible = canAdminister && conversion && eks && v.getSpotPartnerVmId() != null;
            return new SpotSummaryDTO.SpotVm(v.getVmId(), v.getDisplayName(), eks ? "EKS_NODE_GROUP" : "EC2",
                    v.getPurchaseOption().name(), v.isSpotExcluded(), v.isStandby(), partner == null ? null : partner.getDisplayName(),
                    v.isSpotFallbackActive(), v.getStatus() == null ? null : v.getStatus().name(), convertible, revertible);
        }).toList();

        return new SpotSummaryDTO(environmentId, env.getSpotPolicy().name(), Boolean.TRUE.equals(env.getIsProduction()),
                canAdminister, conversion, spotVms.size(), (int) vms.stream().filter(Vm::isStandby).count(),
                vms.stream().anyMatch(Vm::isSpotFallbackActive),
                stateHistoryRepository.countBySourceInEnvironmentSince(SpotInterruptionRecorder.CHANGE_SOURCE, environmentId,
                        Timestamp.from(now.minus(WINDOW))),
                savings, rows);
    }
}
