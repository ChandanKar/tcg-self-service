package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.LeaseDTO;
import com.tcgdigital.vmcontrol.dto.LeasePolicyDTO;
import com.tcgdigital.vmcontrol.service.LeaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Environment leases (E15). Every endpoint answers 404 while lease.enabled is false.
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
@Tag(name = "Environment Leases", description = "End times for running environments")
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @GetMapping("/lease")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the active lease", description = "The environment's active lease, or 204 when none is running")
    public ResponseEntity<LeaseDTO> getLease(@Parameter(description = "Environment ID") @PathVariable String environmentId) {
        return leaseService.getActiveLeaseDto(environmentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/lease-policy")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the lease policy", description = "Opt-in, default and maximum lease length, with the platform limits")
    public ResponseEntity<LeasePolicyDTO> getPolicy(@Parameter(description = "Environment ID") @PathVariable String environmentId) {
        return ResponseEntity.ok(leaseService.getPolicy(environmentId));
    }

    @PutMapping("/lease-policy")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Change the lease policy", description = "Owners only; values must stay within the platform limits")
    public ResponseEntity<LeasePolicyDTO> updatePolicy(@Parameter(description = "Environment ID") @PathVariable String environmentId,
                                                       @Valid @RequestBody LeasePolicyDTO dto) {
        return ResponseEntity.ok(leaseService.updatePolicy(environmentId, dto));
    }
}
