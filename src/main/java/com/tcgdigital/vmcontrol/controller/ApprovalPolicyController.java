package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.ApprovalPolicyDTO;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.service.ApprovalPolicyService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Approval policies (E25-T03): admins manage all of them; those who administer an environment
 * manage that environment's policies. The dry run needs view rights on the environment.
 */
@RestController
@RequestMapping("/api/v1/approval-policies")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Approval policies", description = "When starts and extensions need approval")
public class ApprovalPolicyController {

    private final ApprovalPolicyService policyService;
    private final SecurityService securityService;
    private final UserService userService;

    public ApprovalPolicyController(ApprovalPolicyService policyService, SecurityService securityService, UserService userService) {
        this.policyService = policyService;
        this.securityService = securityService;
        this.userService = userService;
    }

    /** ADMIN for global and team policies; otherwise whoever administers the environment. */
    private void assertCanManage(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            if (!securityService.isAdmin()) {
                throw new UnauthorizedException("Only admins manage global and team approval policies");
            }
            return;
        }
        securityService.assertCanAdminister(environmentId);
    }

    @GetMapping
    @Operation(summary = "Policies; with environmentId only that environment's own")
    public List<ApprovalPolicyDTO> list(@RequestParam(required = false) String environmentId) {
        assertCanManage(environmentId);
        return policyService.list(environmentId == null || environmentId.isBlank() ? null : environmentId);
    }

    @GetMapping("/{policyId}")
    public ApprovalPolicyDTO get(@PathVariable String policyId) {
        assertCanManage(policyService.environmentOf(policyId));
        return policyService.get(policyId);
    }

    @PostMapping
    public ResponseEntity<ApprovalPolicyDTO> create(@RequestBody ApprovalPolicyDTO.Request request) {
        if (request != null && request.teamId() != null && !request.teamId().isBlank()) {
            assertCanManage(null);
        } else {
            assertCanManage(request == null ? null : request.environmentId());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(policyService.create(userService.getCurrentUserId(), request));
    }

    @PutMapping("/{policyId}")
    public ApprovalPolicyDTO update(@PathVariable String policyId, @RequestBody ApprovalPolicyDTO.Request request) {
        assertCanManage(policyService.environmentOf(policyId));
        return policyService.update(policyId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{policyId}")
    public ResponseEntity<Void> delete(@PathVariable String policyId) {
        assertCanManage(policyService.environmentOf(policyId));
        policyService.delete(policyId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/evaluate")
    @Operation(summary = "Dry run: would this start (or extension) need approval, and why")
    public ApprovalPolicyService.ApprovalDecision evaluate(@RequestBody ApprovalPolicyDTO.EvaluateRequest request) {
        if (request == null || request.environmentId() == null || request.environmentId().isBlank()) {
            throw new ValidationException("environmentId is required");
        }
        securityService.assertCanView(request.environmentId());
        return policyService.evaluate(policyService.context(request.environmentId(), request.groupId(), request.vmId(),
                request.hours(), Boolean.TRUE.equals(request.extension()), userService.getCurrentUserId()));
    }
}
