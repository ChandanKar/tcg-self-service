package com.tcgdigital.vmcontrol.dto;

import java.time.Instant;
import java.util.List;

/**
 * A scheduled stop or start that acts on part of an environment (U23-T04): when, the groups or VMs it
 * acts on in words ("Group-2", "Group-2 and Group-3", "web-01") and the ids of the groups it touches.
 */
public record ScopedFiringDTO(Instant at, String scope, List<String> groupIds) {}
