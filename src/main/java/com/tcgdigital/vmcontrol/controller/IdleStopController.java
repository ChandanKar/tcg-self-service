package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.IdleStopRuleDTO;
import com.tcgdigital.vmcontrol.dto.IdleStopRuleRequestDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.IdleStopRuleService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Idle auto-stop configuration of one environment (E16, G5), and the production flag that
 * excludes an environment from it.
 */
@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
@Tag(name = "Idle auto-stop", description = "Stop idle environments automatically")
public class IdleStopController {

    private final IdleStopRuleService ruleService;
    private final SecurityService securityService;
    private final UserService userService;
    private final EnvironmentRepository environmentRepository;
    private final AuditService auditService;

    public IdleStopController(IdleStopRuleService ruleService, SecurityService securityService, UserService userService,
                              EnvironmentRepository environmentRepository, AuditService auditService) {
        this.ruleService = ruleService;
        this.securityService = securityService;
        this.userService = userService;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
    }

    @GetMapping("/idle-stop/rules")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List idle-stop rules", description = "The environment's idle auto-stop rules.")
    public ResponseEntity<List<IdleStopRuleDTO>> listRules(@PathVariable String environmentId) {
        securityService.assertCanView(environmentId);
        return ResponseEntity.ok(ruleService.listRules(environmentId));
    }

    @PostMapping("/idle-stop/rules")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Create an idle-stop rule", description = "New rules start in dry-run mode.")
    public ResponseEntity<IdleStopRuleDTO> createRule(@PathVariable String environmentId,
                                                      @Valid @RequestBody IdleStopRuleRequestDTO dto) {
        securityService.assertCanAdminister(environmentId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ruleService.createRule(environmentId, dto, userService.getCurrentUserId()));
    }

    @PutMapping("/idle-stop/rules/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Update an idle-stop rule",
            description = "Switching to ENFORCE needs a completed dry run unless the caller is an ADMIN.")
    public ResponseEntity<IdleStopRuleDTO> updateRule(@PathVariable String environmentId, @PathVariable String ruleId,
                                                      @Valid @RequestBody IdleStopRuleRequestDTO dto) {
        securityService.assertSameEnvironment(ruleService.getRule(environmentId, ruleId).getEnvironment().getEnvironmentId(),
                environmentId);
        securityService.assertCanAdminister(environmentId);
        return ResponseEntity.ok(ruleService.updateRule(environmentId, ruleId, dto, userService.getCurrentUserId(),
                securityService.isAdmin()));
    }

    @DeleteMapping("/idle-stop/rules/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Delete an idle-stop rule")
    public ResponseEntity<Void> deleteRule(@PathVariable String environmentId, @PathVariable String ruleId) {
        securityService.assertSameEnvironment(ruleService.getRule(environmentId, ruleId).getEnvironment().getEnvironmentId(),
                environmentId);
        securityService.assertCanAdminister(environmentId);
        ruleService.deleteRule(environmentId, ruleId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /** Admin only: mark an environment as production (excluded from idle auto-stop). */
    @PatchMapping("/production")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mark an environment as production",
            description = "Production environments are excluded from idle auto-stop. Body: {\"production\": true|false}")
    public ResponseEntity<Map<String, Object>> setProduction(@PathVariable String environmentId,
                                                             @RequestBody Map<String, Boolean> body) {
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        boolean production = Boolean.TRUE.equals(body.get("production"));
        environment.setIsProduction(production);
        environmentRepository.save(environment);
        auditService.logEnvironmentAction(userService.getCurrentUserId(), AuditAction.ENVIRONMENT_UPDATED,
                environmentId, environment.getName(), "environment", environmentId, environment.getName(),
                production ? "Marked as production (excluded from idle auto-stop)" : "Production mark removed");
        return ResponseEntity.ok(Map.of("environmentId", environmentId, "production", production));
    }
}
