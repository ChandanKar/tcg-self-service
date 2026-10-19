package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceChoiceDTO;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.Ec2Service;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.support.InstanceRefCodec;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EC2 instances to register as VMs. Instance ids are for administrators only (U01-T01/T02): the list
 * offers each instance by name with an opaque reference, and registering sends the reference back.
 */
@RestController
@RequestMapping("/api/ec2")
@Tag(name = "EC2 Instances", description = "Operations for managing AWS EC2 instances")
@PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
public class Ec2Controller {

    private static final Set<String> STALE_STATES = Set.of("terminated", "shutting-down");

    private final Ec2Service ec2Service;
    private final VmRepository vmRepository;
    private final InstanceRefCodec instanceRefs;
    private final UserService userService;
    private final SecurityService securityService;

    public Ec2Controller(Ec2Service ec2Service, VmRepository vmRepository, InstanceRefCodec instanceRefs,
                         UserService userService, SecurityService securityService) {
        this.ec2Service = ec2Service;
        this.vmRepository = vmRepository;
        this.instanceRefs = instanceRefs;
        this.userService = userService;
        this.securityService = securityService;
    }

    /** @deprecated the instance list carries each instance's registration; kept for administrators' scripts. */
    @Deprecated
    @GetMapping("/registered-ids")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Get all registered provider VM IDs (administrators only)",
            description = "Returns every EC2 instance ID already registered in the platform. Deprecated: "
                    + "GET /api/ec2/instances reports each instance's registration."
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
            summary = "List EC2 instances to register",
            description = "The instances in the region, each with an opaque reference to register it by, its name, "
                    + "size, state, IP, zone and whether it is already registered. Instance ids are included for "
                    + "administrators only."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved list of EC2 instances",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = Ec2InstanceChoiceDTO.class))
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
    public ResponseEntity<List<Ec2InstanceChoiceDTO>> listInstances(
            @Parameter(
                    description = "AWS Region (e.g., us-east-1, eu-west-1, ap-south-1)",
                    required = true,
                    example = "us-east-1"
            )
            @RequestParam String region,
            @Parameter(description = "AWS account ref (E28-T10); the default account when omitted")
            @RequestParam(required = false) String accountRef
    ) {
        List<Ec2InstanceInfo> instances = ec2Service.listInstances(accountRef, region);
        String userId = userService.getCurrentUserId();
        List<String> ids = instances.stream().map(Ec2InstanceInfo::instanceId).toList();
        Map<String, Vm> registered = ids.isEmpty() ? Map.of()
                : vmRepository.findByProviderVmIdsFetchEnvironment(CloudProvider.AWS, ids).stream()
                        .collect(Collectors.toMap(Vm::getProviderVmId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(instances.stream()
                .map(instance -> choice(instance, registered.get(instance.instanceId()), accountRef, region, userId))
                .toList());
    }

    private Ec2InstanceChoiceDTO choice(Ec2InstanceInfo instance, Vm vm, String accountRef, String region, String userId) {
        String name = instance.tags() == null ? null : instance.tags().get("Name");
        String environmentName = null;
        String groupName = null;
        if (vm != null) {
            Environment environment = vm.getGroup().getEnvironment();
            boolean visible = securityService.canViewEnvironment(environment.getEnvironmentId());
            String label = environment.getDisplayName() == null || environment.getDisplayName().isBlank()
                    ? environment.getName() : environment.getDisplayName();
            environmentName = visible ? label : "another environment";
            groupName = visible ? vm.getGroup().getDisplayName() : null;
        }
        return new Ec2InstanceChoiceDTO(
                instanceRefs.encode(CloudProvider.AWS, accountRef, region, instance.instanceId(), userId),
                instance.instanceId(),
                name == null || name.isBlank() ? null : name,
                instance.instanceType(),
                instance.state(),
                instance.privateIpAddress(),
                instance.availabilityZone(),
                instance.launchTime(),
                instance.platform(),
                vm != null,
                vm != null && STALE_STATES.contains(instance.state()),
                vm == null ? null : vm.getVmId(),
                environmentName,
                groupName,
                vm != null && Boolean.TRUE.equals(vm.getDiscoveryPending()));
    }
}
