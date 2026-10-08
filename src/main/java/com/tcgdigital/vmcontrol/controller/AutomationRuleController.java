package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AutomationRuleDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleRequestDTO;
import com.tcgdigital.vmcontrol.service.AutomationRuleService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Automation Rules — calendar-schedule and
 * access-grant/lock-acquire triggers for auto start/stop of an environment.
 */
@RestController
@RequestMapping("/api/v1/automation-rules")
@Tag(name = "Automation Rules", description = "Operations for managing calendar-schedule and access-grant automation rules")
public class AutomationRuleController {

    private final AutomationRuleService automationRuleService;
    private final UserService userService;
    private final SecurityService securityService;

    public AutomationRuleController(AutomationRuleService automationRuleService, UserService userService,
                                    SecurityService securityService) {
        this.automationRuleService = automationRuleService;
        this.userService = userService;
        this.securityService = securityService;
    }

    /** 403 unless the caller administers the environment the rule belongs to. */
    private void assertCanAdministerRule(String ruleId) {
        securityService.assertCanAdminister(automationRuleService.getRule(ruleId).getEnvironmentId());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "List automation rules", description = "Lists all automation rules, optionally filtered by environment")
    public ResponseEntity<List<AutomationRuleDTO>> list(
            @Parameter(description = "Optional environment ID filter") @RequestParam(required = false) String environmentId) {
        if (environmentId != null) {
            securityService.assertCanAdminister(environmentId);
            return ResponseEntity.ok(automationRuleService.listRules(environmentId));
        }
        java.util.Map<String, Boolean> administers = new java.util.HashMap<>();
        return ResponseEntity.ok(automationRuleService.listRules(null).stream()
                .filter(rule -> administers.computeIfAbsent(rule.getEnvironmentId(), securityService::canAdministerEnvironment))
                .toList());
    }

    @GetMapping("/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Get an automation rule")
    public ResponseEntity<AutomationRuleDTO> get(@PathVariable String ruleId) {
        assertCanAdministerRule(ruleId);
        return ResponseEntity.ok(automationRuleService.getRule(ruleId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Create an automation rule")
    public ResponseEntity<AutomationRuleDTO> create(@Valid @RequestBody AutomationRuleRequestDTO dto) {
        securityService.assertCanAdminister(dto.getEnvironmentId());
        String userId = userService.getCurrentUserId();
        return ResponseEntity.ok(automationRuleService.createRule(userId, dto));
    }

    @PutMapping("/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Update an automation rule")
    public ResponseEntity<AutomationRuleDTO> update(@PathVariable String ruleId,
                                                     @Valid @RequestBody AutomationRuleRequestDTO dto) {
        assertCanAdministerRule(ruleId);
        if (dto.getEnvironmentId() != null) {
            securityService.assertCanAdminister(dto.getEnvironmentId()); // moving the rule
        }
        String userId = userService.getCurrentUserId();
        return ResponseEntity.ok(automationRuleService.updateRule(ruleId, userId, dto));
    }

    @PatchMapping("/{ruleId}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Enable or disable an automation rule")
    public ResponseEntity<AutomationRuleDTO> setEnabled(@PathVariable String ruleId,
                                                        @RequestBody Map<String, Boolean> body) {
        assertCanAdministerRule(ruleId);
        String userId = userService.getCurrentUserId();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ResponseEntity.ok(automationRuleService.setEnabled(ruleId, enabled, userId));
    }

    @DeleteMapping("/{ruleId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Delete an automation rule")
    public ResponseEntity<Void> delete(@PathVariable String ruleId) {
        assertCanAdministerRule(ruleId);
        String userId = userService.getCurrentUserId();
        automationRuleService.deleteRule(ruleId, userId);
        return ResponseEntity.ok().build();
    }
}
