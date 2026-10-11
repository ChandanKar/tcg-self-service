package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CostSplitRuleDTO;
import com.tcgdigital.vmcontrol.service.CostSplitService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Shared-cost split rules (E23-T05), for admins. */
@RestController
@RequestMapping("/api/v1/cost-management/split-rules")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Cost split rules", description = "How shared environments' cost is charged to teams")
public class CostSplitRuleController {

    private final CostSplitService splitService;
    private final UserService userService;

    public CostSplitRuleController(CostSplitService splitService, UserService userService) {
        this.splitService = splitService;
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List split rules with environment and team names")
    public List<CostSplitRuleDTO> list() {
        return splitService.list();
    }

    @GetMapping("/{ruleId}")
    @Operation(summary = "Get one split rule")
    public CostSplitRuleDTO get(@PathVariable String ruleId) {
        return splitService.get(ruleId);
    }

    @PostMapping
    @Operation(summary = "Create a split rule (one per environment; FIXED_PERCENT shares sum to 100)")
    public ResponseEntity<CostSplitRuleDTO> create(@RequestBody CostSplitRuleDTO.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(splitService.create(userService.getCurrentUserId(), request));
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Change a split rule's method, start month or shares")
    public CostSplitRuleDTO update(@PathVariable String ruleId, @RequestBody CostSplitRuleDTO.Request request) {
        return splitService.update(ruleId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{ruleId}")
    @Operation(summary = "Delete a split rule; the environment is charged to its own team again")
    public ResponseEntity<Void> delete(@PathVariable String ruleId) {
        splitService.delete(ruleId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
