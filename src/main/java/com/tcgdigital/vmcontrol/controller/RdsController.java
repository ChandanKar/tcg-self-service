package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.RdsCloudProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Browsing RDS databases to register them as VMs (E33-T02); 404 while rds.enabled is off. */
@RestController
@RequestMapping("/api/v1/rds")
@Tag(name = "RDS", description = "RDS DB instances and Aurora clusters")
@PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
public class RdsController {

    private final RdsCloudProviderService rdsService;
    private final VmRepository vmRepository;

    public RdsController(RdsCloudProviderService rdsService, VmRepository vmRepository) {
        this.rdsService = rdsService;
        this.vmRepository = vmRepository;
    }

    /** Whether RDS databases can be browsed; the registry shows its EC2 / RDS toggle only then. */
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("enabled", rdsService.isEnabled()));
    }

    @GetMapping("/databases")
    @Operation(summary = "List RDS databases", description = "DB instances (not Aurora cluster members) and DB clusters of an account and region.")
    public ResponseEntity<List<RdsDatabaseInfoDTO>> databases(@RequestParam String region,
                                                              @RequestParam(required = false) String accountRef) {
        if (!rdsService.isEnabled()) {
            throw new ResourceNotFoundException("RDS support is not enabled");
        }
        Set<String> registered = new HashSet<>(vmRepository.findAllProviderVmIdsByProvider(CloudProvider.AWS_RDS));
        return ResponseEntity.ok(rdsService.listDatabases(CloudTarget.of(accountRef, region)).stream()
                .map(db -> db.withRegistered(registered.contains(db.providerVmId())))
                .toList());
    }
}
