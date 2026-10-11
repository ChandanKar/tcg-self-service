package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.DecideLeaseExtensionDTO;
import com.tcgdigital.vmcontrol.dto.ExtendLeaseDTO;
import com.tcgdigital.vmcontrol.dto.LeaseDTO;
import com.tcgdigital.vmcontrol.dto.LeaseExtensionRequestDTO;
import com.tcgdigital.vmcontrol.dto.LeasePolicyDTO;
import com.tcgdigital.vmcontrol.service.LeaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Environment leases (E15). Every endpoint answers 404 while lease.enabled is false.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Environment Leases", description = "End times for running environments")
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @GetMapping("/environments/{environmentId}/lease")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the active lease", description = "The environment's active lease, or 204 when none is running")
    public ResponseEntity<LeaseDTO> getLease(@Parameter(description = "Environment ID") @PathVariable String environmentId) {
        return leaseService.getActiveLeaseDto(environmentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/environments/{environmentId}/lease-policy")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the lease policy", description = "Opt-in, default and maximum lease length, with the platform limits")
    public ResponseEntity<LeasePolicyDTO> getPolicy(@Parameter(description = "Environment ID") @PathVariable String environmentId) {
        return ResponseEntity.ok(leaseService.getPolicy(environmentId));
    }

    @PutMapping("/environments/{environmentId}/lease-policy")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Change the lease policy", description = "Owners only; values must stay within the platform limits")
    public ResponseEntity<LeasePolicyDTO> updatePolicy(@Parameter(description = "Environment ID") @PathVariable String environmentId,
                                                       @Valid @RequestBody LeasePolicyDTO dto) {
        return ResponseEntity.ok(leaseService.updatePolicy(environmentId, dto));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.service.ApprovalRequestService approvalRequestService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.service.UserService userService;

    @PostMapping("/environments/{environmentId}/lease/extend")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Extend the running lease",
            description = "200 with the lease when within the owner's maximum; 202 with a request the owners must approve beyond it")
    public ResponseEntity<?> extend(@Parameter(description = "Environment ID") @PathVariable String environmentId,
                                    @Valid @RequestBody ExtendLeaseDTO dto) {
        // Beyond an approval policy the extension waits for its approvers (E25-T05).
        com.tcgdigital.vmcontrol.dto.ApprovalRequestDTO.Held held = approvalRequestService.holdExtensionIfNeeded(
                environmentId, userService.getCurrentUserId(), dto.getMinutes(), dto.getReason());
        if (held != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(held);
        }
        LeaseService.ExtendOutcome outcome = leaseService.extend(environmentId, dto.getMinutes(), dto.getReason());
        return outcome.applied()
                ? ResponseEntity.ok(outcome.lease())
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.request());
    }

    @GetMapping("/environments/{environmentId}/lease/extension-requests")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Pending lease extension requests", description = "For the environment's owners")
    public ResponseEntity<List<LeaseExtensionRequestDTO>> extensionRequests(
            @Parameter(description = "Environment ID") @PathVariable String environmentId) {
        return ResponseEntity.ok(leaseService.pendingExtensions(environmentId));
    }

    @PostMapping("/lease-extension-requests/{requestId}/approve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approve a lease extension", description = "Owners only; 409 if already decided")
    public ResponseEntity<LeaseExtensionRequestDTO> approve(@PathVariable String requestId,
                                                            @Valid @RequestBody(required = false) DecideLeaseExtensionDTO dto) {
        return ResponseEntity.ok(leaseService.decideExtension(requestId, true, dto == null ? null : dto.getNotes()));
    }

    @PostMapping("/lease-extension-requests/{requestId}/deny")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Deny a lease extension", description = "Owners only; 409 if already decided")
    public ResponseEntity<LeaseExtensionRequestDTO> deny(@PathVariable String requestId,
                                                         @Valid @RequestBody(required = false) DecideLeaseExtensionDTO dto) {
        return ResponseEntity.ok(leaseService.decideExtension(requestId, false, dto == null ? null : dto.getNotes()));
    }
}
