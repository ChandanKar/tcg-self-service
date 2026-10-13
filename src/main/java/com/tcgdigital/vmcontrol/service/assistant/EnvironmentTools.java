package com.tcgdigital.vmcontrol.service.assistant;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.MAX_ROWS;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.prop;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.schema;
import static com.tcgdigital.vmcontrol.service.assistant.AssistantScope.string;

/** Environment tools for the assistant (E36-T02): list and describe the environments the caller may see. */
@Configuration
public class EnvironmentTools {

    private final AssistantScope scope;
    private final EnvironmentService environmentService;

    public EnvironmentTools(AssistantScope scope, EnvironmentService environmentService) {
        this.scope = scope;
        this.environmentService = environmentService;
    }

    @Bean
    public AssistantTool listEnvironmentsTool() {
        return new SimpleTool("list_environments",
                "Lists the environments you can see (at most 50): name, VM counts, running VMs, regions, team, AWS account and whether leases apply. "
                        + "Optionally filter by a search text in the name or display name.",
                schema(Map.of("search", prop("string", "Text to look for in the environment's name or display name"))),
                args -> {
                    String search = string(args, "search");
                    List<Environment> envs = new ArrayList<>(scope.viewable().values());
                    if (search != null) {
                        String q = search.toLowerCase(Locale.ROOT);
                        envs.removeIf(e -> !e.getName().toLowerCase(Locale.ROOT).contains(q)
                                && (e.getDisplayName() == null || !e.getDisplayName().toLowerCase(Locale.ROOT).contains(q)));
                    }
                    List<Environment> shown = envs.stream().limit(MAX_ROWS).toList();
                    Map<String, EnvironmentService.EnvironmentCounts> counts =
                            environmentService.getBatchCounts(shown.stream().map(Environment::getEnvironmentId).toList());
                    List<Map<String, Object>> rows = shown.stream().map(e -> row(e, counts.get(e.getEnvironmentId()))).toList();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("environments", rows);
                    result.put("total", envs.size());
                    result.put("truncated", envs.size() > MAX_ROWS);
                    return result;
                });
    }

    @Bean
    public AssistantTool getEnvironmentTool() {
        return new SimpleTool("get_environment",
                "Describes one environment you can see by its id: VM counts, running VMs, regions, team, AWS account and whether leases apply.",
                schema(Map.of("environmentId", prop("string", "The environment's id (from list_environments)")), "environmentId"),
                args -> {
                    Environment env = scope.environment(string(args, "environmentId"));
                    return row(env, environmentService.getBatchCounts(List.of(env.getEnvironmentId())).get(env.getEnvironmentId()));
                });
    }

    private static Map<String, Object> row(Environment e, EnvironmentService.EnvironmentCounts c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", e.getEnvironmentId());
        r.put("name", e.getName());
        r.put("displayName", e.getDisplayName());
        r.put("groups", c == null ? 0 : c.groupCount());
        r.put("vms", c == null ? 0 : c.vmCount());
        r.put("runningVms", c == null ? 0 : c.runningVmCount());
        r.put("regions", c == null ? List.of() : c.regions());
        r.put("team", e.getTeam() == null ? null : e.getTeam().getName());
        r.put("awsAccount", e.getAwsAccountRef());
        r.put("leaseEnabled", Boolean.TRUE.equals(e.getLeaseEnabled()));
        return r;
    }
}
