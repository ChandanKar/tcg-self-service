package com.tcgdigital.vmcontrol.service.assistant;

import com.tcgdigital.vmcontrol.dto.EnvironmentCostDTO;
import com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.CostEstimationService;
import com.tcgdigital.vmcontrol.service.EnvironmentCostService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.MAX_ROWS;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.enumProp;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.integer;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.money;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.prop;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.schema;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.string;

/**
 * Cost tools for the assistant (E36-T02), over the environments the caller may see. Figures are
 * the platform's estimates (static pricing and runtime) unless labelled actual (Cost Explorer).
 */
@Configuration
public class CostTools {

    private final AssistantScope scope;
    private final EnvironmentCostService environmentCostService;
    private final CostEstimationService costEstimationService;

    public CostTools(AssistantScope scope, EnvironmentCostService environmentCostService, CostEstimationService costEstimationService) {
        this.scope = scope;
        this.environmentCostService = environmentCostService;
        this.costEstimationService = costEstimationService;
    }

    @Bean
    public AssistantTool getEnvironmentCostTool() {
        return new SimpleTool("get_environment_cost",
                "One environment's cost this month: month-to-date estimate and actual (when ingested), change against last month, "
                        + "month-end forecast, savings and its five most expensive VMs.",
                schema(Map.of("environmentId", prop("string", "The environment's id")), "environmentId"),
                args -> {
                    Environment env = scope.environment(string(args, "environmentId"));
                    EnvironmentCostDTO c = environmentCostService.getCost(env.getEnvironmentId(), scope.user());
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("environment", env.getName());
                    r.put("currency", "USD");
                    r.put("basis", "estimated (static pricing); actual from Cost Explorer when ingested");
                    r.put("monthStart", c.monthStart() == null ? null : c.monthStart().toString());
                    r.put("monthToDateEstimated", money(c.monthToDateEstimated()));
                    r.put("monthToDateActual", money(c.monthToDateActual()));
                    r.put("actualDays", c.actualDays());
                    r.put("changePercentVsLastMonth", c.changePercent());
                    r.put("forecastMonthEnd", money(c.forecastMonthEnd()));
                    r.put("forecastBasis", c.forecastBasis());
                    r.put("savingsMonthToDate", money(c.savingsMtd()));
                    r.put("topVms", c.topVms() == null ? List.of() : c.topVms().stream().limit(5).map(v -> {
                        Map<String, Object> vm = new LinkedHashMap<>();
                        vm.put("name", v.name());
                        vm.put("monthToDateCost", money(v.mtdCost()));
                        vm.put("status", v.status());
                        vm.put("costKnown", v.costKnown());
                        return vm;
                    }).toList());
                    return r;
                });
    }

    @Bean
    public AssistantTool spendByTool() {
        return new SimpleTool("spend_by",
                "Estimated spend over the last 30 days, grouped by environment, team or AWS account, for the environments you can see.",
                schema(Map.of("dimension", enumProp("How to group the spend", "environment", "team", "account")), "dimension"),
                args -> {
                    String dimension = string(args, "dimension");
                    if (dimension == null || !List.of("environment", "team", "account").contains(dimension)) {
                        throw new IllegalArgumentException("dimension must be environment, team or account");
                    }
                    Map<String, Environment> envs = scope.viewable();
                    Map<String, BigDecimal> totals = new TreeMap<>();
                    Map<String, Integer> vmCounts = new TreeMap<>();
                    for (SpendByDimensionDTO row : costEstimationService.getSpendByEnvironment()) {
                        Environment env = envs.get(row.dimensionKey());
                        if (env == null) continue;
                        String key = switch (dimension) {
                            case "team" -> env.getTeam() == null ? "Unassigned" : env.getTeam().getName();
                            case "account" -> env.getAwsAccountRef() == null ? "default" : env.getAwsAccountRef();
                            default -> env.getName();
                        };
                        totals.merge(key, row.cost() == null ? BigDecimal.ZERO : row.cost(), BigDecimal::add);
                        vmCounts.merge(key, row.vmCount() == null ? 0 : row.vmCount(), Integer::sum);
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    totals.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder()))
                            .limit(MAX_ROWS).forEach(e -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put(dimension, e.getKey());
                                r.put("cost", money(e.getValue()));
                                r.put("vms", vmCounts.get(e.getKey()));
                                rows.add(r);
                            });
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("dimension", dimension);
                    result.put("period", "last 30 days");
                    result.put("currency", "USD");
                    result.put("basis", "estimated (static pricing)");
                    result.put("rows", rows);
                    result.put("total", money(totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
                    return result;
                });
    }

    @Bean
    public AssistantTool costTrendTool() {
        return new SimpleTool("cost_trend",
                "Daily cost this month for one environment you can see, or summed over all of them: estimated and, when "
                        + "ingested, actual. 'days' keeps only the most recent days.",
                schema(Map.of(
                        "environmentId", prop("string", "Limit to this environment (optional)"),
                        "days", prop("integer", "How many recent days to return, 1-31 (default 31)"))),
                args -> {
                    int days = integer(args, "days", 1, 31, 31);
                    String environmentId = string(args, "environmentId");
                    List<Environment> envs = environmentId != null ? List.of(scope.environment(environmentId))
                            : new ArrayList<>(scope.viewable().values()).stream().limit(MAX_ROWS).toList();
                    TreeMap<LocalDate, BigDecimal[]> byDay = new TreeMap<>();
                    for (Environment env : envs) {
                        EnvironmentCostDTO c = environmentCostService.getCost(env.getEnvironmentId(), scope.user());
                        if (c.daily() == null) continue;
                        for (EnvironmentCostDTO.DailyCost d : c.daily()) {
                            BigDecimal[] sum = byDay.computeIfAbsent(d.date(), k -> new BigDecimal[]{BigDecimal.ZERO, null});
                            if (d.estimated() != null) sum[0] = sum[0].add(d.estimated());
                            if (d.actual() != null) sum[1] = sum[1] == null ? d.actual() : sum[1].add(d.actual());
                        }
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    byDay.descendingMap().entrySet().stream().limit(days).forEach(e -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("date", e.getKey().toString());
                        r.put("estimated", money(e.getValue()[0]));
                        r.put("actual", money(e.getValue()[1]));
                        rows.add(r);
                    });
                    java.util.Collections.reverse(rows);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("scope", environmentId != null ? envs.get(0).getName() : envs.size() + " environment(s)");
                    result.put("currency", "USD");
                    result.put("days", rows);
                    return result;
                });
    }
}
