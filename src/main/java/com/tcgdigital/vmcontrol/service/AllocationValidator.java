package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Required allocation fields (E23-T04), so no spend lands in "Unassigned": with
 * allocation.require-team an environment must belong to a team, and every key in
 * allocation.required-metadata-keys must be a non-blank metadata value. Both are off by default;
 * turn them on once the allocation-gaps list is empty.
 */
@Component
public class AllocationValidator {

    private final ObjectMapper objectMapper;

    @Value("${allocation.require-team:false}")
    private boolean requireTeam = false;

    @Value("${allocation.required-metadata-keys:}")
    private String requiredMetadataKeys = "";

    public AllocationValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The rules, for forms that mark required fields. */
    public record Rules(boolean requireTeam, List<String> requiredMetadataKeys) {}

    public Rules rules() {
        return new Rules(requireTeam, metadataKeys());
    }

    List<String> metadataKeys() {
        return Arrays.stream(requiredMetadataKeys == null ? new String[0] : requiredMetadataKeys.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** The missing fields ("team", or a metadata key); empty when the environment is allocated. */
    public List<String> missing(Environment environment) {
        List<String> missing = new ArrayList<>();
        if (requireTeam && environment.getTeam() == null) {
            missing.add("team");
        }
        List<String> keys = metadataKeys();
        if (!keys.isEmpty()) {
            JsonNode metadata = parse(environment.getMetadata());
            for (String key : keys) {
                JsonNode value = metadata == null ? null : metadata.get(key);
                if (value == null || value.isNull() || value.asText().isBlank()) {
                    missing.add(key);
                }
            }
        }
        return missing;
    }

    /** 400 "Missing allocation fields: team, costCenter" when anything is missing. */
    public void validate(Environment environment) {
        List<String> missing = missing(environment);
        if (!missing.isEmpty()) {
            throw new ValidationException("Missing allocation fields: " + String.join(", ", missing));
        }
    }

    private JsonNode parse(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(metadata);
        } catch (Exception e) {
            return null;
        }
    }
}
