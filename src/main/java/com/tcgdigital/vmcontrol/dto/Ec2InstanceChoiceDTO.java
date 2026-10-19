package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.dto.json.AdminOnly;

import java.time.Instant;

/**
 * An EC2 instance offered for registration (U01-T02): picked by name, registered with {@code ref}.
 * The instance id itself is for administrators only.
 *
 * @param ref                       opaque, short-lived reference to send back as RegisterVmDTO.instanceRef
 * @param instanceId                administrators only
 * @param name                      the Name tag; null when the instance has none
 * @param registered                already in the platform (in a group, or found by discovery)
 * @param stale                     registered, but AWS reports it terminated or shutting down
 * @param registeredVmId            the VM it is registered as, when registered
 * @param registeredEnvironmentName where it is registered; "another environment" when the caller cannot view it
 * @param discoveryPending          found by discovery and not yet placed in a group
 */
public record Ec2InstanceChoiceDTO(
        String ref,
        @AdminOnly String instanceId,
        String name,
        String instanceType,
        String state,
        String privateIpAddress,
        String availabilityZone,
        Instant launchTime,
        String platform,
        boolean registered,
        boolean stale,
        String registeredVmId,
        String registeredEnvironmentName,
        String registeredGroupName,
        boolean discoveryPending) {
}
