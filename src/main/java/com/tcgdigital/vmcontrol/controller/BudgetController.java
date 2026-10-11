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

    public BudgetController(BudgetService budgetService, UserService userService) {
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
