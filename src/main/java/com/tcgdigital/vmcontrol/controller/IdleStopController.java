package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.IdleStopRuleDTO;
import com.tcgdigital.vmcontrol.dto.IdleStopStatusDTO;
import com.tcgdigital.vmcontrol.repository.IdleStopEventRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.idle.IdleStopSnoozeService;
import com.tcgdigital.vmcontrol.service.idle.IdleStopSummaryService;
import org.springframework.beans.factory.annotation.Value;
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
    private final IdleStopSnoozeService snoozeService;
    private final IdleStopSummaryService summaryService;
    private final IdleStopEventRepository eventRepository;
    private final UserRepository userRepository;

    @Value("${automation.idle-stop.enabled:false}")
    private boolean featureEnabled;

    public IdleStopController(IdleStopRuleService ruleService, SecurityService securityService, UserService userService,
                              EnvironmentRepository environmentRepository, AuditService auditService,
                              IdleStopSnoozeService snoozeService, IdleStopSummaryService summaryService,
                              IdleStopEventRepository eventRepository, UserRepository userRepository) {
        this.ruleService = ruleService;
        this.securityService = securityService;
        this.userService = userService;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
        this.snoozeService = snoozeService;
        this.summaryService = summaryService;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
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

    @GetMapping("/idle-stop/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Idle auto-stop status",
            description = "Rules, snooze, the latest decision and the last days' would-have-saved / saved amounts.")
    public ResponseEntity<IdleStopStatusDTO> status(@PathVariable String environmentId,
                                                    @RequestParam(defaultValue = "14") int days) {
        securityService.assertCanView(environmentId);
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        var snooze = snoozeService.getActive(environmentId).orElse(null);
        String snoozedByName = snooze == null || snooze.getSnoozedByUserId() == null ? null
                : userRepository.findById(snooze.getSnoozedByUserId()).map(u -> u.getDisplayName()).orElse(null);
        var latest = eventRepository.findTop20ByEnvironmentIdOrderByEvaluatedAtDesc(environmentId).stream().findFirst()
                .map(e -> new IdleStopStatusDTO.LatestEvent(e.getOutcome(), e.getReason(), e.getEvaluatedAt(), e.getIdleSince()))
                .orElse(null);
        var summary = summaryService.summarize(environmentId, days);
        return ResponseEntity.ok(new IdleStopStatusDTO(featureEnabled, Boolean.TRUE.equals(environment.getIsProduction()),
                ruleService.listRules(environmentId),
                snooze == null ? null : snooze.getSnoozedUntil(), snooze == null ? null : snooze.getSnoozedByUserId(),
                snoozedByName, latest, Math.max(1, Math.min(days, 31)), summary.wouldStopEpisodes(), summary.wouldHaveSaved(),
                summary.stoppedCount(), summary.savedEstimate(), summary.stoppedSavings()));
    }

    @PostMapping("/idle-stop/snooze")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Snooze idle auto-stop", description = "For 1, 4 or 8 hours; extends an active snooze.")
    public ResponseEntity<Map<String, Object>> snooze(@PathVariable String environmentId,
                                                      @RequestBody Map<String, Object> body) {
        securityService.assertCanOperate(environmentId);
        Integer hours = body.get("hours") instanceof Number n ? n.intValue() : null;
        String reason = body.get("reason") instanceof String r ? r : null;
        var snooze = snoozeService.snooze(environmentId, hours, reason, userService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("environmentId", environmentId, "snoozedUntil", snooze.getSnoozedUntil()));
    }

    @DeleteMapping("/idle-stop/snooze")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "End the snooze", description = "Allowed for the person who snoozed or an environment admin.")
    public ResponseEntity<Void> endSnooze(@PathVariable String environmentId) {
        securityService.assertCanOperate(environmentId);
        snoozeService.cancel(environmentId, userService.getCurrentUserId(),
                securityService.canAdministerEnvironment(environmentId));
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
