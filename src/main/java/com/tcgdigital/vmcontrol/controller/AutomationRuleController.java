package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AutomationRuleDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleOverrideDTOs.OverrideDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleOverrideDTOs.OverrideRequest;
import com.tcgdigital.vmcontrol.dto.AutomationRuleRequestDTO;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
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

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Preview the next runs of a draft schedule rule",
            description = "Validated like a create but not saved. Pass ruleId when editing a saved rule to apply its overrides.")
    public List<com.tcgdigital.vmcontrol.dto.ScheduleRunPreviewDTO> preview(
            @Valid @RequestBody AutomationRuleRequestDTO dto,
            @RequestParam(required = false) String ruleId,
            @RequestParam(defaultValue = "10") int limit) {
        securityService.assertCanAdminister(dto.getEnvironmentId());
        if (ruleId != null && !ruleId.isBlank()) {
            assertCanAdministerRule(ruleId);
        }
        return automationRuleService.previewRuns(dto, ruleId, limit);
    }

    @GetMapping("/{ruleId}/next-runs")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "The next runs of a saved schedule rule, with skipped ones marked")
    public List<com.tcgdigital.vmcontrol.dto.ScheduleRunPreviewDTO> nextRuns(@PathVariable String ruleId,
                                                                            @RequestParam(defaultValue = "10") int limit) {
        assertCanAdministerRule(ruleId);
        return automationRuleService.nextRuns(ruleId, limit);
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

    // ============= Overrides (E19-T03) =============

    /** Longest override a non-admin may set (a 'snooze'). */
    private static final java.time.Duration MAX_SNOOZE = java.time.Duration.ofHours(24);

    /** Admins of the rule's environment, or anyone who can operate in it. */
    private String assertCanSeeOverrides(String ruleId) {
        String environmentId = automationRuleService.getRule(ruleId).getEnvironmentId();
        if (!securityService.canAdministerEnvironment(environmentId) && !securityService.canOperateInEnvironment(environmentId)) {
            throw new UnauthorizedException("You cannot override rules in this environment");
        }
        return environmentId;
    }

    @GetMapping("/{ruleId}/overrides")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List a rule's active and upcoming overrides")
    public List<OverrideDTO> overrides(@PathVariable String ruleId) {
        assertCanSeeOverrides(ruleId);
        return automationRuleService.listOverrides(ruleId);
    }

    @PostMapping("/{ruleId}/overrides")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Skip a rule's START, STOP or ALL actions for a time range",
            description = "Admins may override for up to 30 days; anyone who can operate in the environment for up to 24 hours (a snooze).")
    public OverrideDTO createOverride(@PathVariable String ruleId, @Valid @RequestBody OverrideRequest request) {
        String environmentId = assertCanSeeOverrides(ruleId);
        if (!securityService.canAdministerEnvironment(environmentId)
                && request.endsAt().isAfter(java.time.Instant.now().plus(MAX_SNOOZE))) {
            throw new UnauthorizedException("Overrides ending more than 24 hours from now need an admin");
        }
        return automationRuleService.createOverride(ruleId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{ruleId}/overrides/{overrideId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Remove an override (admins, or the person who set it)")
    public ResponseEntity<Void> deleteOverride(@PathVariable String ruleId, @PathVariable String overrideId) {
        String environmentId = assertCanSeeOverrides(ruleId);
        String userId = userService.getCurrentUserId();
        if (!securityService.canAdministerEnvironment(environmentId)
                && !userId.equals(automationRuleService.overrideCreator(ruleId, overrideId))) {
            throw new UnauthorizedException("Only an admin or the person who set it can remove an override");
        }
        automationRuleService.deleteOverride(ruleId, overrideId, userId);
        return ResponseEntity.noContent().build();
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
