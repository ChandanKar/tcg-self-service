package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An environment's AWS region (U19-T04): the region in its metadata, else the one region all its
 * VMs share. Nothing else: an environment with neither has no known region, and the callers report
 * it ("set it in the environment's settings") instead of trying aws.region.
 */
@Service
public class EnvironmentRegionResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VmRepository vmRepository;

    public EnvironmentRegionResolver(VmRepository vmRepository) {
        this.vmRepository = vmRepository;
    }

    /** The "region" key of an environment's metadata JSON, when it is a non-blank string. */
    public static Optional<String> metadataRegion(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode region = MAPPER.readTree(metadata).get("region");
            return region != null && region.isTextual() && !region.asText().isBlank()
                    ? Optional.of(region.asText().trim()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The metadata region, else the single region of the environment's VMs; empty when unknown or mixed. */
    public Optional<String> regionOf(Environment environment) {
        Optional<String> stored = metadataRegion(environment.getMetadata());
        if (stored.isPresent()) {
            return stored;
        }
        List<String> regions = vmRegions(environment.getEnvironmentId());
        return regions.size() == 1 ? Optional.of(regions.get(0)) : Optional.empty();
    }

    /** The distinct regions of the environment's VMs, sorted. */
    public List<String> vmRegions(String environmentId) {
        return vmRepository.findDistinctRegionsGroupedByEnvironment(List.of(environmentId)).stream()
                .map(VmRepository.EnvironmentRegion::getRegion)
                .filter(r -> r != null && !r.isBlank()).distinct().sorted().toList();
    }

    /** The environments whose region is unknown: no metadata region and no VM with a region (one query for all). */
    public List<Environment> withoutRegion(Collection<Environment> environments) {
        List<Environment> candidates = environments.stream().filter(e -> metadataRegion(e.getMetadata()).isEmpty()).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> regions = new HashMap<>();
        for (VmRepository.EnvironmentRegion row : vmRepository.findDistinctRegionsGroupedByEnvironment(
                candidates.stream().map(Environment::getEnvironmentId).toList())) {
            if (row.getRegion() != null && !row.getRegion().isBlank()) {
                regions.computeIfAbsent(row.getEnvironmentId(), k -> new LinkedHashSet<>()).add(row.getRegion());
            }
        }
        return candidates.stream().filter(e -> !regions.containsKey(e.getEnvironmentId())).toList();
    }
}
