package com.tcgdigital.vmcontrol.service.assistant;

import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.dto.SavingsBreakdownRowDTO;
import com.tcgdigital.vmcontrol.dto.SavingsSummaryDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.CostEstimationService;
import com.tcgdigital.vmcontrol.service.SavingsReportService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.MAX_ROWS;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.enumProp;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.limit;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.money;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.prop;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.schema;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.string;

/** Savings, idle and rightsizing tools for the assistant (E36-T02), over the environments the caller may see. */
@Configuration
public class OptimizationTools {

    /** Enough to hold every VM's row before the caller's environments are kept. */
    private static final PageRequest ALL = PageRequest.of(0, 10_000);

    private final AssistantScope scope;
    private final SavingsReportService savingsReportService;
    private final CostEstimationService costEstimationService;

    public OptimizationTools(AssistantScope scope, SavingsReportService savingsReportService,
                             CostEstimationService costEstimationService) {
        this.scope = scope;
        this.savingsReportService = savingsReportService;
        this.costEstimationService = costEstimationService;
    }

    @Bean
    public AssistantTool savingsSummaryTool() {
        return new SimpleTool("savings_summary",
                "Realised savings for a month (stopped hours priced from static pricing), by cause, with the potential savings "
                        + "and cost efficiency; for one environment or every environment you can see.",
                schema(Map.of(
                        "month", prop("string", "The month as yyyy-MM (default: this month)"),
                        "environmentId", prop("string", "Limit to this environment (optional)"))),
                args -> {
                    YearMonth month = month(string(args, "month"));
                    String environmentId = string(args, "environmentId");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("month", month.toString());
                    r.put("currency", "USD");
                    r.put("basis", "estimated (static pricing)");
                    if (environmentId != null || scope.isAdmin()) {
                        String id = environmentId == null ? null : scope.environment(environmentId).getEnvironmentId();
                        SavingsSummaryDTO s = savingsReportService.summary(month, id);
                        r.put("scope", id == null ? "all environments" : scope.environment(id).getName());
                        r.put("realisedNet", money(s.realisedNet()));
                        r.put("realisedGross", money(s.realisedGross()));
                        r.put("byCause", rows(s.byCause()));
                        r.put("potentialSavings", money(s.potentialSavings()));
                        r.put("efficiencyPercent", s.efficiencyPercent());
                        return r;
                    }
                    Map<String, Environment> envs = scope.viewable();
                    List<SavingsBreakdownRowDTO> mine = savingsReportService.byEnvironment(month).stream()
                            .filter(row -> envs.containsKey(row.key())).toList();
                    r.put("scope", envs.size() + " environment(s)");
                    r.put("realisedNet", money(mine.stream().map(SavingsBreakdownRowDTO::net)
                            .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add)));
                    r.put("byEnvironment", rows(mine));
                    return r;
                });
    }

    @Bean
    public AssistantTool idleResourcesTool() {
        return new SimpleTool("idle_resources",
                "Idle VMs in the environments you can see, most expensive first: how long idle, latest CPU and the estimated "
                        + "monthly cost of the idle time.",
                schema(Map.of(
                        "environmentId", prop("string", "Limit to this environment (optional)"),
                        "limit", prop("integer", "At most this many rows, 1-50 (default 20)"))),
                args -> {
                    Map<String, Environment> envs = envs(string(args, "environmentId"));
                    List<IdleWasteRowDTO> all = costEstimationService.getIdleWaste(ALL).getContent().stream()
                            .filter(row -> envs.containsKey(row.environmentId())).toList();
                    List<Map<String, Object>> rows = all.stream().limit(limit(args, 20)).map(row -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("vm", row.vmName());
                        m.put("environment", row.environmentName());
                        m.put("group", row.groupName());
                        m.put("idleMinutes", row.idleDurationMinutes());
                        m.put("latestCpuPercent", row.latestCpuUtilization());
                        m.put("monthlyCost", money(row.monthlyCost()));
                        m.put("monthlyIdleCost", money(row.monthlyIdleCost()));
                        m.put("costKnown", !Boolean.FALSE.equals(row.costKnown()));
                        return m;
                    }).toList();
                    return listResult("idle", rows, all.size());
                });
    }

    @Bean
    public AssistantTool rightsizingCandidatesTool() {
        return new SimpleTool("rightsizing_candidates",
                "VMs in the environments you can see that could change instance type, largest saving first: current and "
                        + "suggested type, CPU and memory use, and the estimated monthly saving.",
                schema(Map.of(
                        "environmentId", prop("string", "Limit to this environment (optional)"),
                        "direction", enumProp("Only this kind of change (optional)", "SCALE_DOWN", "SCALE_UP", "MODERNIZE"),
                        "limit", prop("integer", "At most this many rows, 1-50 (default 20)"))),
                args -> {
                    String direction = string(args, "direction");
                    if (direction != null && !List.of("SCALE_DOWN", "SCALE_UP", "MODERNIZE").contains(direction)) {
                        throw new IllegalArgumentException("direction must be SCALE_DOWN, SCALE_UP or MODERNIZE");
                    }
                    Map<String, Environment> envs = envs(string(args, "environmentId"));
                    List<RightsizingCandidateDTO> all = costEstimationService.getRightsizingCandidates(direction, ALL).getContent()
                            .stream().filter(c -> envs.containsKey(c.environmentId())).toList();
                    List<Map<String, Object>> rows = all.stream().limit(limit(args, 20)).map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("vm", c.vmName());
                        m.put("environment", c.environmentName());
                        m.put("direction", c.direction());
                        m.put("currentType", c.currentInstanceType());
                        m.put("suggestedType", c.suggestedInstanceType());
                        m.put("avgCpuPercent", c.avgCpuUtilization());
                        m.put("peakCpuPercent", c.peakCpuUtilization());
                        m.put("peakMemoryPercent", c.peakMemoryUtilization());
                        m.put("currentMonthlyCost", money(c.currentMonthlyCost()));
                        m.put("estimatedMonthlySavings", money(c.estimatedMonthlySavings()));
                        m.put("source", c.source());
                        m.put("reasons", c.reasons());
                        return m;
                    }).toList();
                    return listResult("candidates", rows, all.size());
                });
    }

    private Map<String, Environment> envs(String environmentId) {
        if (environmentId == null) return scope.viewable();
        Environment env = scope.environment(environmentId);
        return Map.of(env.getEnvironmentId(), env);
    }

    private static Map<String, Object> listResult(String key, List<Map<String, Object>> rows, int total) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("currency", "USD");
        r.put("basis", "estimated (static pricing, last 30 days)");
        r.put(key, rows);
        r.put("total", total);
        r.put("truncated", total > rows.size());
        return r;
    }

    private static List<Map<String, Object>> rows(List<SavingsBreakdownRowDTO> rows) {
        if (rows == null) return List.of();
        return rows.stream().limit(MAX_ROWS).map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", row.key());
            m.put("label", row.label());
            m.put("net", money(row.net()));
            m.put("gross", money(row.gross()));
            m.put("hours", row.hours());
            return m;
        }).toList();
    }

    private static YearMonth month(String text) {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        if (text == null) return now;
        YearMonth month;
        try {
            month = YearMonth.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be yyyy-MM");
        }
        if (month.isAfter(now)) throw new IllegalArgumentException("month must not be in the future");
        return month;
    }
}
