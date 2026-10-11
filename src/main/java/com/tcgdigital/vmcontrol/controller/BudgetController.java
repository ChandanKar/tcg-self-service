package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.BudgetDTO;
import com.tcgdigital.vmcontrol.dto.BudgetRequestDTO;
import com.tcgdigital.vmcontrol.service.BudgetService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Monthly budgets (E20-T01). Who may read or change a budget is decided by {@link BudgetService}:
 * admins any; an environment's owners its budget; team budgets admins only.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Budgets", description = "Monthly budgets per environment or team")
public class BudgetController {

    private final BudgetService budgetService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.service.BudgetActionService budgetActionService;

    public BudgetController(BudgetService budgetService, UserService userService,
                            com.tcgdigital.vmcontrol.service.BudgetActionService budgetActionService) {
        this.budgetActionService = budgetActionService;
        this.budgetService = budgetService;
        this.userService = userService;
    }

    @GetMapping("/budgets")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List budgets (admins: all; others: those of environments they can view)")
    public List<BudgetDTO> list() {
        return budgetService.list();
    }

    @GetMapping("/budgets/teams")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Teams that environments belong to, for team budgets")
    public List<String> teams() {
        return budgetService.teams();
    }

    // ============= Actions (E20-T03) =============

    /** Body of an approve/reject/lift call. */
    public record DecisionRequest(@jakarta.validation.constraints.Size(max = 500) String reason) {}

    @GetMapping("/budgets/actions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Budget action requests of environments you own (admins: all)")
    public List<com.tcgdigital.vmcontrol.dto.BudgetActionRequestDTO> actions(
            @RequestParam(required = false) com.tcgdigital.vmcontrol.model.BudgetActionRequest.Status status) {
        return budgetActionService.list(userService.getCurrentUserId(), status);
    }

    @PostMapping("/budgets/actions/{requestId}/approve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approve a budget action (an owner other than the budget's creator, or an admin)")
    public com.tcgdigital.vmcontrol.dto.BudgetActionRequestDTO approve(@PathVariable String requestId,
                                                                     @Valid @RequestBody(required = false) DecisionRequest body) {
        return budgetActionService.approve(requestId, userService.getCurrentUserId(), body == null ? null : body.reason());
    }

    @PostMapping("/budgets/actions/{requestId}/reject")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reject a budget action")
    public com.tcgdigital.vmcontrol.dto.BudgetActionRequestDTO reject(@PathVariable String requestId,
                                                                    @Valid @RequestBody(required = false) DecisionRequest body) {
        return budgetActionService.reject(requestId, userService.getCurrentUserId(), body == null ? null : body.reason());
    }

    @PostMapping("/environments/{environmentId}/freeze/lift")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lift an environment's budget start freeze")
    public ResponseEntity<Void> liftFreeze(@PathVariable String environmentId,
                                           @Valid @RequestBody(required = false) DecisionRequest body) {
        budgetActionService.liftFreeze(environmentId, userService.getCurrentUserId(), body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/budgets/{budgetId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a budget with this month's spend and forecast")
    public BudgetDTO get(@PathVariable String budgetId) {
        return budgetService.get(budgetId);
    }

    @PostMapping("/budgets")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a budget")
    public ResponseEntity<BudgetDTO> create(@Valid @RequestBody BudgetRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.create(userService.getCurrentUserId(), request));
    }

    @PutMapping("/budgets/{budgetId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update a budget (its scope cannot change)")
    public BudgetDTO update(@PathVariable String budgetId, @Valid @RequestBody BudgetRequestDTO request) {
        return budgetService.update(budgetId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/budgets/{budgetId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete a budget")
    public ResponseEntity<Void> delete(@PathVariable String budgetId) {
        budgetService.delete(budgetId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/environments/{environmentId}/budget")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "An environment's budget (204 when it has none)")
    public ResponseEntity<BudgetDTO> forEnvironment(@PathVariable String environmentId) {
        return budgetService.forEnvironment(environmentId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
