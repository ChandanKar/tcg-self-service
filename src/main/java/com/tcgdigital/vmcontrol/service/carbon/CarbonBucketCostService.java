package com.tcgdigital.vmcontrol.service.carbon;

import com.tcgdigital.vmcontrol.service.CostExplorerClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.DimensionValues;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.Group;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole account's cost per region and service for a month (E37-T02), so an environment's carbon
 * share is its part of everything that service cost in that account and region, not only of what the
 * platform tracks. One billed Cost Explorer call per region the platform has resources in, for the
 * services the platform runs (EC2, EKS, RDS). Without credentials, with carbon.attribution.cost-explorer
 * off, or when a call fails, nothing is returned and the platform's own costs are the totals.
 */
@Service
public class CarbonBucketCostService {

    private static final Logger log = LoggerFactory.getLogger(CarbonBucketCostService.class);

    /** Cost Explorer's service names for the carbon export's product codes. */
    static final Map<String, String> SERVICE_CODES = Map.of(
            "Amazon Elastic Compute Cloud - Compute", "AmazonEC2",
            "EC2 - Other", "AmazonEC2",
            "Amazon Relational Database Service", "AmazonRDS",
            "Amazon Elastic Container Service for Kubernetes", "AmazonEKS");

    @Value("${carbon.attribution.cost-explorer:true}")
    private boolean useCostExplorer = true;

    private final CostExplorerClientProvider clientProvider;

    public CarbonBucketCostService(CostExplorerClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    /** Cost by {@link CarbonAttributionService#bucket(String, String, String)} key; empty when unavailable. */
    public Map<String, BigDecimal> costs(YearMonth month, Collection<String> regions) {
        if (!useCostExplorer || regions.isEmpty() || !clientProvider.isConfigured()) {
            return Map.of();
        }
        Map<String, BigDecimal> costs = new HashMap<>();
        try {
            for (String region : regions) {
                GetCostAndUsageResponse response = clientProvider.client().getCostAndUsage(GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder().start(month.atDay(1).toString())
                                .end(month.plusMonths(1).atDay(1).toString()).build())
                        .granularity(Granularity.MONTHLY)
                        .metrics("UnblendedCost")
                        .filter(Expression.builder().and(
                                Expression.builder().dimensions(DimensionValues.builder().key("SERVICE")
                                        .values(SERVICE_CODES.keySet()).build()).build(),
                                Expression.builder().dimensions(DimensionValues.builder().key("REGION")
                                        .values(List.of(region)).build()).build()).build())
                        .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("LINKED_ACCOUNT").build(),
                                GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build())
                        .build());
                for (ResultByTime period : response.resultsByTime()) {
                    for (Group g : period.groups()) {
                        String code = g.keys().size() < 2 ? null : SERVICE_CODES.get(g.keys().get(1));
                        if (code == null || g.metrics().get("UnblendedCost") == null) continue;
                        BigDecimal amount = new BigDecimal(g.metrics().get("UnblendedCost").amount());
                        costs.merge(CarbonAttributionService.bucket(g.keys().get(0), region, code), amount, BigDecimal::add);
                    }
                }
            }
            return costs;
        } catch (Exception e) {
            log.warn("Cost Explorer totals for carbon attribution of {} unavailable, using platform costs: {}", month, e.getMessage());
            return Map.of();
        }
    }
}
