package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.FoundInAwsDTO;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.discovery.DiscoveryInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * "Found in AWS — needs review" (U19-T03): what discovery found in any account and region that no
 * environment holds, for administrators to add or ignore. Instance ids reach administrators only.
 */
@RestController
@RequestMapping("/api/v1/discovery")
@Tag(name = "Discovery", description = "Resources found in AWS that need review")
public class DiscoveryController {

    /** What to ignore: an instance by its reference, or a cluster by account, region and name. */
    public record IgnoreRequest(String kind, String ref, String accountRef, String region, String clusterName) { }

    private final DiscoveryInboxService inbox;
    private final UserService userService;

    public DiscoveryController(DiscoveryInboxService inbox, UserService userService) {
        this.inbox = inbox;
        this.userService = userService;
    }

    @GetMapping("/found")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Found in AWS",
            description = "EC2 instances and EKS clusters in every account and enabled region that no environment holds and "
                    + "nobody ignored, with the regions that could not be read. The sweep is kept 10 minutes; refresh=true sweeps again.")
    public ResponseEntity<FoundInAwsDTO> found(
            @Parameter(description = "Sweep again now") @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(inbox.inbox(refresh, userService.getCurrentUserId()));
    }

    @PostMapping("/ignored")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ignore a found resource", description = "Hides it from Found in AWS until it is brought back.")
    public ResponseEntity<Void> ignore(@RequestBody IgnoreRequest request) {
        inbox.ignore(request.kind(), request.ref(), request.accountRef(), request.region(), request.clusterName(),
                userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ignored")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ignored resources", description = "Newest first.")
    public ResponseEntity<List<FoundInAwsDTO.Ignored>> ignored() {
        return ResponseEntity.ok(inbox.ignored());
    }

    @DeleteMapping("/ignored/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Bring an ignored resource back", description = "It shows in Found in AWS again.")
    public ResponseEntity<Void> unignore(@PathVariable String id) {
        inbox.unignore(id, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
