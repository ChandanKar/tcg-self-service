package com.tcgdigital.vmcontrol.assistant;

import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.SpendByDimensionDTO;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.service.CostEstimationService;
import com.tcgdigital.vmcontrol.service.EnvironmentCostService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.SavingsReportService;
import com.tcgdigital.vmcontrol.service.assistant.AssistantScope;
import com.tcgdigital.vmcontrol.service.assistant.AssistantTool;
import com.tcgdigital.vmcontrol.service.assistant.CostTools;
import com.tcgdigital.vmcontrol.service.assistant.EnvironmentTools;
import com.tcgdigital.vmcontrol.service.assistant.OptimizationTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each assistant tool's schema and mapping (E36-T02), without a database. */
class AssistantToolsUnitTest {

    private final AssistantScope scope = mock(AssistantScope.class);
    private final EnvironmentService environmentService = mock(EnvironmentService.class);
    private final EnvironmentCostService environmentCostService = mock(EnvironmentCostService.class);
    private final CostEstimationService costEstimationService = mock(CostEstimationService.class);
    private final SavingsReportService savingsReportService = mock(SavingsReportService.class);

    private EnvironmentTools environmentTools;
    private CostTools costTools;
    private OptimizationTools optimizationTools;
    private Environment a;
    private Environment b;

    private static Environment env(String id, String name, String team, String account) {
        Environment e = new Environment();
        e.setEnvironmentId(id);
        e.setName(name);
        if (team != null) {
            Team t = new Team();
            t.setName(team);
            e.setTeam(t);
        }
        e.setAwsAccountRef(account);
        return e;
    }

    @BeforeEach
    void setUp() {
        environmentTools = new EnvironmentTools(scope, environmentService);
        costTools = new CostTools(scope, environmentCostService, costEstimationService);
        optimizationTools = new OptimizationTools(scope, savingsReportService, costEstimationService);
        a = env("a", "alpha", "Payments", "prod");
        b = env("b", "beta", "Payments", "dev");
        Map<String, Environment> viewable = new LinkedHashMap<>();
        viewable.put("a", a);
        viewable.put("b", b);
        when(scope.viewable()).thenReturn(viewable);
    }

    private List<AssistantTool> all() {
        return List.of(environmentTools.listEnvironmentsTool(), environmentTools.getEnvironmentTool(),
                costTools.getEnvironmentCostTool(), costTools.spendByTool(), costTools.costTrendTool(),
                optimizationTools.savingsSummaryTool(), optimizationTools.idleResourcesTool(),
                optimizationTools.rightsizingCandidatesTool());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everySchemaIsATypedObjectWhoseRequiredFieldsExist() {
        for (AssistantTool tool : all()) {
            Map<String, Object> schema = tool.inputSchema();
            assertThat(tool.name()).matches("[a-z_]+");
            assertThat(tool.description()).as(tool.name()).isNotBlank();
            assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
            Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) schema.get("properties");
            props.forEach((k, p) -> {
                assertThat(p).as(tool.name() + "." + k).containsKey("type");
                assertThat((String) p.get("description")).as(tool.name() + "." + k).isNotBlank();
            });
            ((List<String>) schema.getOrDefault("required", List.of())).forEach(r -> assertThat(props).containsKey(r));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void spendByTeamSumsTheCallersEnvironmentsOnly() {
        when(costEstimationService.getSpendByEnvironment()).thenReturn(List.of(
                new SpendByDimensionDTO("a", "alpha", new BigDecimal("10.005"), 2),
                new SpendByDimensionDTO("b", "beta", new BigDecimal("5"), 1),
                new SpendByDimensionDTO("hidden", "hidden", new BigDecimal("999"), 9)));

        Map<String, Object> result = (Map<String, Object>) costTools.spendByTool().call(Map.of("dimension", "team"));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("team", "Payments").containsEntry("cost", new BigDecimal("15.01")).containsEntry("vms", 3);
        assertThat(result).containsEntry("total", new BigDecimal("15.01")).containsEntry("currency", "USD");

        Map<String, Object> byAccount = (Map<String, Object>) costTools.spendByTool().call(Map.of("dimension", "account"));
        assertThat((List<Map<String, Object>>) byAccount.get("rows")).extracting(r -> r.get("account")).containsExactly("prod", "dev");
    }

    @Test
    @SuppressWarnings("unchecked")
    void idleResourcesKeepsTheCallersEnvironmentsAndCapsTheRows() {
        List<IdleWasteRowDTO> rows = new java.util.ArrayList<>(IntStream.range(0, 60).mapToObj(i -> new IdleWasteRowDTO("vm" + i, "vm" + i, "a",
                "alpha", "web", new BigDecimal("100"), true, 120, null, BigDecimal.ONE, new BigDecimal("12.345"))).toList());
        rows.add(new IdleWasteRowDTO("x", "x", "hidden", "hidden", "web", BigDecimal.TEN, true, 1, null, BigDecimal.ONE, BigDecimal.ONE));
        when(costEstimationService.getIdleWaste(any(Pageable.class))).thenReturn(new PageImpl<>(rows));

        Map<String, Object> result = (Map<String, Object>) optimizationTools.idleResourcesTool().call(Map.of("limit", 500));

        List<Map<String, Object>> idle = (List<Map<String, Object>>) result.get("idle");
        assertThat(idle).hasSize(50);
        assertThat(idle.get(0)).containsEntry("monthlyIdleCost", new BigDecimal("12.35")).doesNotContainKey("environmentId");
        assertThat(result).containsEntry("total", 60).containsEntry("truncated", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listEnvironmentsFiltersBySearch() {
        when(environmentService.getBatchCounts(any())).thenReturn(Map.of("b", new EnvironmentService.EnvironmentCounts(1, 3, 2, List.of("eu-west-1"))));

        Map<String, Object> result = (Map<String, Object>) environmentTools.listEnvironmentsTool().call(Map.of("search", "BET"));

        List<Map<String, Object>> envs = (List<Map<String, Object>>) result.get("environments");
        assertThat(envs).hasSize(1);
        assertThat(envs.get(0)).containsEntry("id", "b").containsEntry("vms", 3).containsEntry("runningVms", 2)
                .containsEntry("team", "Payments").containsEntry("awsAccount", "dev");
    }
}
