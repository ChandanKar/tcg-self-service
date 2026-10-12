package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.service.SpotPolicyService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Spot opt-in of an environment and per-VM exclusions (E31-T03). */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
@Tag(name = "Spot", description = "Opt environments in to Spot capacity")
public class SpotController {

    private final SpotPolicyService spotPolicyService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.service.SpotConversionService conversionService;

    public SpotController(SpotPolicyService spotPolicyService, UserService userService,
                          com.tcgdigital.vmcontrol.service.SpotConversionService conversionService) {
        this.spotPolicyService = spotPolicyService;
        this.userService = userService;
        this.conversionService = conversionService;
    }

    @PostMapping("/vms/{vmId}/convert-to-spot")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Convert a node group to Spot", description = "Creates a Spot twin and keeps the on-demand node group as standby (E31-T05).")
    public ResponseEntity<Map<String, Object>> convertToSpot(@PathVariable String environmentId, @PathVariable String vmId) {
        conversionService.convertToSpot(environmentId, vmId, userService.getCurrentUserId());
        return ResponseEntity.accepted().body(Map.of("vmId", vmId, "status", "CONVERTING"));
    }

    @PostMapping("/vms/{vmId}/revert-to-on-demand")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Go back to on demand", description = "Starts the standby node group and removes the Spot twin (E31-T05).")
    public ResponseEntity<Map<String, Object>> revertToOnDemand(@PathVariable String environmentId, @PathVariable String vmId) {
        conversionService.revertToOnDemand(environmentId, vmId, userService.getCurrentUserId());
        return ResponseEntity.accepted().body(Map.of("vmId", vmId, "status", "REVERTING"));
    }

    public record SpotPolicyRequest(String policy, Boolean acknowledgeInterruptible) {
    }

    public record SpotExclusionRequest(Boolean excluded) {
    }

    @PutMapping("/spot-policy")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Set the Spot policy", description = "NONE or ALLOWED; ALLOWED needs acknowledgeInterruptible and a non-production environment.")
    public ResponseEntity<Map<String, Object>> setPolicy(@PathVariable String environmentId, @RequestBody SpotPolicyRequest request) {
        SpotPolicy policy;
        try {
            policy = SpotPolicy.valueOf(String.valueOf(request.policy()).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("policy must be NONE or ALLOWED");
        }
        Environment env = spotPolicyService.setPolicy(environmentId, policy,
                Boolean.TRUE.equals(request.acknowledgeInterruptible()), userService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("environmentId", env.getEnvironmentId(), "spotPolicy", env.getSpotPolicy().name()));
    }

    @PutMapping("/vms/{vmId}/spot-excluded")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Exclude a VM from Spot", description = "Keeps one VM or node group of the environment on demand.")
    public ResponseEntity<Map<String, Object>> setExcluded(@PathVariable String environmentId, @PathVariable String vmId,
                                                           @RequestBody SpotExclusionRequest request) {
        if (request.excluded() == null) {
            throw new ValidationException("excluded is required");
        }
        Vm vm = spotPolicyService.setVmExcluded(environmentId, vmId, request.excluded(), userService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("vmId", vm.getVmId(), "spotExcluded", vm.isSpotExcluded()));
    }
}
