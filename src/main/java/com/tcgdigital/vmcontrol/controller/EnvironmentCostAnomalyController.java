package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CostAnomalyDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.service.CostAnomalyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** An environment's cost anomalies, for anyone who can view it (E21-T04). */
@RestController
@Tag(name = "Cost Anomalies")
public class EnvironmentCostAnomalyController {

    private final CostAnomalyService anomalyService;

    public EnvironmentCostAnomalyController(CostAnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @GetMapping("/api/v1/environments/{environmentId}/cost-anomalies")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "An environment's cost anomalies, newest first")
    public List<CostAnomalyDTO> forEnvironment(@PathVariable String environmentId,
                                               @RequestParam(defaultValue = "90") int days) {
        if (days < 1 || days > 365) {
            throw new ValidationException("days must be between 1 and 365");
        }
        return anomalyService.forEnvironment(environmentId, days);
    }
}
