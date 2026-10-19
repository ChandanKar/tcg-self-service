package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CapabilitiesDTO;
import com.tcgdigital.vmcontrol.service.CapabilitiesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's role and the server's feature switches (U02-T01). */
@RestController
@Tag(name = "Users", description = "Operations for managing users")
public class CapabilitiesController {

    private final CapabilitiesService capabilitiesService;

    public CapabilitiesController(CapabilitiesService capabilitiesService) {
        this.capabilitiesService = capabilitiesService;
    }

    @GetMapping("/api/v1/users/me/capabilities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "What I can open",
            description = "Role flags and the on/off state of each optional feature, so the app only opens pages "
                    + "that will work. Every API call is still checked on its own.")
    public ResponseEntity<CapabilitiesDTO> capabilities() {
        return ResponseEntity.ok(capabilitiesService.forCurrentUser());
    }
}
