package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the owning team for an {@code Environment} from its free-text {@code metadata} JSON
 * blob (key {@code "ownerTeam"}). Shared by cost reporting ({@link CostEstimationService}) and
 * cost-allocation tagging ({@link TagReconciliationService}) so both read the same team value.
 */
@Component
public class TeamResolver {

    private static final Logger log = LoggerFactory.getLogger(TeamResolver.class);
    private static final String UNASSIGNED = "Unassigned";

    private final ObjectMapper objectMapper;

    public TeamResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The environment's team (E23-T01): its linked team's display name, else the legacy
     * metadata ownerTeam, else "Unassigned".
     */
    public String resolveTeam(com.tcgdigital.vmcontrol.model.Environment environment) {
        if (environment == null) {
            return UNASSIGNED;
        }
        if (environment.getTeam() != null) {
            return environment.getTeam().getDisplayName();
        }
        return resolveTeam(environment.getMetadata());
    }

    public String resolveTeam(String environmentMetadata) {
        if (environmentMetadata == null || environmentMetadata.isBlank()) {
            return UNASSIGNED;
        }
        try {
            JsonNode node = objectMapper.readTree(environmentMetadata);
            JsonNode team = node.get("ownerTeam");
            if (team != null && !team.isNull() && !team.asText().isBlank()) {
                return team.asText();
            }
        } catch (Exception e) {
            log.debug("Could not parse environment metadata JSON for team lookup: {}", e.getMessage());
        }
        return UNASSIGNED;
    }
}
