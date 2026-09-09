package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.DirectoryUserDTO;
import com.tcgdigital.vmcontrol.service.GraphDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Microsoft Entra ID directory lookup for admin user onboarding.
 * See {@code docs/graph-user-onboarding.md}.
 */
@RestController
@RequestMapping("/api/v1/directory")
@Tag(name = "Directory", description = "Entra ID directory lookup for onboarding users")
public class DirectoryController {

    private final GraphDirectoryService graphDirectoryService;

    public DirectoryController(GraphDirectoryService graphDirectoryService) {
        this.graphDirectoryService = graphDirectoryService;
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Search the Entra ID directory",
            description = "Infix search by display name / email / UPN. Returns 409 when directory "
                    + "lookup is disabled (onboard manually instead), 502 when Graph is unreachable.")
    public ResponseEntity<List<DirectoryUserDTO>> search(
            @Parameter(description = "Search term (min 2 chars after trimming)") @RequestParam String q,
            @Parameter(description = "Max results, 1-25 (default 15)") @RequestParam(required = false) Integer top) {
        return ResponseEntity.ok(graphDirectoryService.search(q, top));
    }
}
