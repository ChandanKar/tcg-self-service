package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.SpotSummaryDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
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
    private final SpotOpportunityService opportunityService;
    private final SpotFallbackService fallbackService;
    private final AuditLogRepository auditLogRepository;

    /** Interruptions listed on the Spot page at most. */
    private static final int RECENT_INTERRUPTIONS = 20;

    public SpotSummaryService(EnvironmentRepository environmentRepository, VmRepository vmRepository,
                              VmStateHistoryRepository stateHistoryRepository, CostDataProvider costDataProvider,
                              SecurityService securityService, SpotPolicyService spotPolicy,
                              SpotConversionService conversionService, SpotOpportunityService opportunityService,
                              SpotFallbackService fallbackService, AuditLogRepository auditLogRepository) {
        this.opportunityService = opportunityService;
        this.fallbackService = fallbackService;
        this.auditLogRepository = auditLogRepository;
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
        Map<String, BigDecimal> savedByVm = new java.util.HashMap<>();
        List<Vm> spotVms = vms.stream().filter(v -> v.getPurchaseOption() == PurchaseOption.SPOT).toList();
        if (!spotVms.isEmpty()) {
            for (Map.Entry<String, CostDataProvider.VmCostEstimate> entry : costDataProvider.estimateCosts(spotVms,
                    Timestamp.from(now.minus(WINDOW)), Timestamp.from(now)).entrySet()) {
                CostDataProvider.VmCostEstimate e = entry.getValue();
                if (e.onDemandEquivalentCost() != null && e.cost() != null) {
                    BigDecimal saved = e.onDemandEquivalentCost().subtract(e.cost());
                    savedByVm.put(entry.getKey(), saved);
                    savings = savings.add(saved);
                }
            }
        }
        Map<String, SpotOpportunityService.Estimate> estimates = opportunityService.estimates(vms);

        List<SpotSummaryDTO.SpotVm> rows = vms.stream().map(v -> {
            boolean eks = v.getProvider() == CloudProvider.AWS_EKS;
            Vm partner = v.getSpotPartnerVmId() == null ? null : byId.get(v.getSpotPartnerVmId());
            boolean convertible = canAdminister && conversion && allowed && eks && !v.isSpotExcluded()
                    && v.getPurchaseOption() == PurchaseOption.ON_DEMAND && !v.isStandby() && v.getSpotPartnerVmId() == null
                    && v.getStatus() == VmStatus.RUNNING;
            boolean revertible = canAdminister && conversion && eks && v.getSpotPartnerVmId() != null;
            SpotOpportunityService.Estimate est = estimates.get(v.getVmId());
            boolean onSpot = v.getPurchaseOption() == PurchaseOption.SPOT;
            BigDecimal monthly = onSpot ? savedByVm.get(v.getVmId()) : (est == null ? null : est.savings());
            return new SpotSummaryDTO.SpotVm(v.getVmId(), v.getDisplayName(), eks ? "EKS_NODE_GROUP" : "EC2",
                    v.getPurchaseOption().name(), v.isSpotExcluded(), v.isStandby(), partner == null ? null : partner.getDisplayName(),
                    v.isSpotFallbackActive(), v.getStatus() == null ? null : v.getStatus().name(), convertible, revertible,
                    est == null ? null : est.instanceType(), est == null ? null : est.nodeCount(), monthly, !onSpot && monthly != null);
        }).toList();

        return new SpotSummaryDTO(environmentId, env.getSpotPolicy().name(), Boolean.TRUE.equals(env.getIsProduction()),
                canAdminister, conversion, spotVms.size(), (int) vms.stream().filter(Vm::isStandby).count(),
                vms.stream().anyMatch(Vm::isSpotFallbackActive),
                stateHistoryRepository.countBySourceInEnvironmentSince(SpotInterruptionRecorder.CHANGE_SOURCE, environmentId,
                        Timestamp.from(now.minus(WINDOW))),
                savings, rows, recentInterruptions(environmentId, now), fallbackService.graceMinutes());
    }

    /**
     * The last 30 days' interruptions, newest first, each with how long the on-demand standby ran: until
     * the next "Spot capacity back" for that node group, or null while it is still running.
     */
    private List<SpotSummaryDTO.Interruption> recentInterruptions(String environmentId, Instant now) {
        Timestamp from = Timestamp.from(now.minus(WINDOW));
        List<VmStateHistory> rows = stateHistoryRepository.findBySourceInEnvironmentSince(
                SpotInterruptionRecorder.CHANGE_SOURCE, environmentId, from).stream().limit(RECENT_INTERRUPTIONS).toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<AuditLog> cleared = auditLogRepository.findByActionAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                AuditAction.SPOT_FALLBACK_CLEARED.name(), rows.stream().map(h -> h.getVm().getVmId()).distinct().toList(), from);
        return rows.stream().map(h -> {
            Long minutes = cleared.stream()
                    .filter(a -> a.getTargetId().equals(h.getVm().getVmId()) && !a.getCreatedAt().before(h.getChangedAt()))
                    .findFirst()
                    .map(a -> Math.max(1, Duration.between(h.getChangedAt().toInstant(), a.getCreatedAt().toInstant()).toMinutes()))
                    .orElse(null);
            return new SpotSummaryDTO.Interruption(h.getVm().getDisplayName(), h.getChangedAt().toInstant(), minutes);
        }).toList();
    }
}
