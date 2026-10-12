package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.SpotOpportunityDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spot opportunities (E31-T04): on-demand VMs and EKS node groups, not excluded, in environments
 * that allow Spot, with what the last 30 days would have cost on Spot and AWS's placement score
 * (how likely the capacity is). EKS node groups can be converted (E31-T05); an EC2 VM would have
 * to be relaunched as Spot, so it is listed for information only.
 */
@Service
public class SpotOpportunityService {

    private static final Logger log = LoggerFactory.getLogger(SpotOpportunityService.class);
    private static final int WINDOW_DAYS = 30;
    private static final Duration SCORE_TTL = Duration.ofHours(24);

    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final CostDataProvider costDataProvider;
    private final PricingReferenceService pricing;
    private final SpotPriceService spotPriceService;
    private final SpotPolicyService spotPolicyService;
    private final AwsCloudProviderService awsCloudProviderService;
    private Clock clock = Clock.systemUTC();

    private record CachedScore(Instant at, Integer score) {
    }

    private final Map<String, CachedScore> scoreCache = new ConcurrentHashMap<>();

    public SpotOpportunityService(VmRepository vmRepository, VmInventorySnapshotRepository inventoryRepository,
                                  CostDataProvider costDataProvider, PricingReferenceService pricing,
                                  SpotPriceService spotPriceService, SpotPolicyService spotPolicyService,
                                  AwsCloudProviderService awsCloudProviderService) {
        this.vmRepository = vmRepository;
        this.inventoryRepository = inventoryRepository;
        this.costDataProvider = costDataProvider;
        this.pricing = pricing;
        this.spotPriceService = spotPriceService;
        this.spotPolicyService = spotPolicyService;
        this.awsCloudProviderService = awsCloudProviderService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Every opportunity, biggest saving first; empty while Spot is off. */
    public List<SpotOpportunityDTO> list() {
        if (!spotPolicyService.isEnabled()) {
            return List.of();
        }
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> v.getProvider() == CloudProvider.AWS || v.getProvider() == CloudProvider.AWS_EKS)
                .filter(v -> v.getPurchaseOption() != PurchaseOption.SPOT && !v.isSpotExcluded())
                .filter(v -> v.getGroup() != null && v.getGroup().getEnvironment() != null
                        && v.getGroup().getEnvironment().getSpotPolicy() == SpotPolicy.ALLOWED)
                .toList();
        if (vms.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        Map<String, CostDataProvider.VmCostEstimate> estimates = costDataProvider.estimateCosts(vms,
                Timestamp.from(now.minus(Duration.ofDays(WINDOW_DAYS))), Timestamp.from(now));
        Map<String, String> typeByVmId = new HashMap<>();
        List<String> ec2Ids = vms.stream().filter(v -> v.getProvider() == CloudProvider.AWS).map(Vm::getVmId).toList();
        if (!ec2Ids.isEmpty()) {
            inventoryRepository.findInstanceTypesByVmIds(ec2Ids).forEach(p -> typeByVmId.put(p.getVmId(), p.getInstanceType()));
        }

        List<SpotOpportunityDTO> rows = new ArrayList<>();
        for (Vm vm : vms) {
            boolean eks = vm.getProvider() == CloudProvider.AWS_EKS;
            EstimatedCostProvider.EksNodeInfo info = eks ? EstimatedCostProvider.eksNodeInfo(vm.getMetadata()) : null;
            String type = eks ? info.instanceType() : typeByVmId.get(vm.getVmId());
            int nodes = eks ? Math.max(1, info.nodeCount()) : 1;
            CostDataProvider.VmCostEstimate estimate = estimates.get(vm.getVmId());
            BigDecimal hours = estimate == null || estimate.runtimeHours() == null ? BigDecimal.ZERO : estimate.runtimeHours();
            PricingReferenceService.PriceLookupResult onDemand = pricing.lookupHourlyRate("AWS", type, vm.getRegion());
            BigDecimal spotRate = spotPriceService.lookupSpotRate(type, vm.getRegion(), null).orElse(null);
            BigDecimal onDemandMonthly = onDemand.priceKnown() ? money(onDemand.hourlyRate(), hours, nodes) : null;
            BigDecimal spotMonthly = spotRate != null ? money(spotRate, hours, nodes) : null;
            BigDecimal savings = onDemandMonthly != null && spotMonthly != null ? onDemandMonthly.subtract(spotMonthly) : null;
            rows.add(new SpotOpportunityDTO(vm.getVmId(), vm.getDisplayName(),
                    vm.getGroup().getEnvironment().getEnvironmentId(), vm.getGroup().getEnvironment().getDisplayName(),
                    eks ? "EKS_NODE_GROUP" : "EC2", type, eks ? nodes : null, onDemandMonthly, spotMonthly, savings,
                    type == null ? null : placementScore(CloudTarget.of(vm), type, nodes),
                    eks, eks ? null : "Requires relaunch as a Spot instance"));
        }
        rows.sort(Comparator.comparing((SpotOpportunityDTO r) -> r.savings() == null ? BigDecimal.valueOf(-1) : r.savings()).reversed());
        return rows;
    }

    private static BigDecimal money(BigDecimal rate, BigDecimal hours, int nodes) {
        return rate.multiply(hours).multiply(BigDecimal.valueOf(nodes)).setScale(2, RoundingMode.HALF_UP);
    }

    /** AWS's 1-10 score for the type and its same-shape alternatives, cached for a day; null when unknown. */
    private Integer placementScore(CloudTarget target, String type, int capacity) {
        List<String> types = pricing.sameShapeTypes(type);
        String key = target.key() + "|" + String.join(",", types) + "|" + capacity;
        CachedScore cached = scoreCache.get(key);
        if (cached != null && cached.at().plus(SCORE_TTL).isAfter(clock.instant())) {
            return cached.score();
        }
        Integer score;
        try {
            score = awsCloudProviderService.getSpotPlacementScore(target, types, capacity);
        } catch (RuntimeException e) {
            log.debug("No Spot placement score for {} in {}: {}", type, target, e.getMessage());
            score = null; // e.g. AccessDenied: shown as unknown, and not asked again today
        }
        scoreCache.put(key, new CachedScore(clock.instant(), score));
        return score;
    }
}
