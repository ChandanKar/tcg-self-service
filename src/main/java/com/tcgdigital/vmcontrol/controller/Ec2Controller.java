package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.Ec2Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ec2")
@Tag(name = "EC2 Instances", description = "Operations for managing AWS EC2 instances")
@PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
public class Ec2Controller {

    private final Ec2Service ec2Service;
    private final VmRepository vmRepository;

    public Ec2Controller(Ec2Service ec2Service, VmRepository vmRepository) {
        this.ec2Service = ec2Service;
        this.vmRepository = vmRepository;
    }

    @GetMapping("/registered-ids")
    @Operation(
            summary = "Get all registered provider VM IDs",
            description = "Returns a list of all EC2 instance IDs that are already registered in the platform across all environments"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved registered instance IDs",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = String.class))
                    )
            )
    })
    public ResponseEntity<List<String>> getRegisteredInstanceIds() {
        List<String> registeredIds = vmRepository.findAllProviderVmIdsByProvider(CloudProvider.AWS);
        return ResponseEntity.ok(registeredIds);
    }

    @GetMapping("/instances")
    @Operation(
            summary = "List all EC2 instances",
            description = "Retrieves a list of all EC2 instances in the specified AWS region with detailed information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of EC2 instances",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = Ec2InstanceInfo.class))
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid region provided",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content
            )
    })
    public ResponseEntity<List<Ec2InstanceInfo>> listInstances(
            @Parameter(
                    description = "AWS Region (e.g., us-east-1, eu-west-1, ap-south-1)",
                    required = true,
                    example = "us-east-1"
            )
            @RequestParam String region
    ) {
        List<Ec2InstanceInfo> instances = ec2Service.listInstances(region);
        return ResponseEntity.ok(instances);
    }
}
