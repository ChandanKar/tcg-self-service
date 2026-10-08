package com.tcgdigital.vmcontrol.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.exception.ValidationException;

import java.util.Iterator;
import java.util.Map;

/**
 * Applies an edit's metadata as a patch over the stored metadata (M4): an edit form that only
 * knows some keys (ownerTeam, defaultCloudProvider) must not drop the others (an EKS region).
 * Keys in the patch override, keys with a null value are removed, other keys are kept.
 */
public final class JsonMetadataMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonMetadataMerger() {
    }

    public static String merge(String existing, String patch) {
        if (patch == null) {
            return existing;
        }
        ObjectNode patchNode = parseObject(patch, "Metadata must be a JSON object");
        ObjectNode result;
        try {
            result = existing == null || existing.isBlank() ? MAPPER.createObjectNode()
                    : parseObject(existing, "Stored metadata is not a JSON object");
        } catch (ValidationException e) {
            result = MAPPER.createObjectNode(); // unreadable stored metadata: the patch replaces it
        }
        Iterator<Map.Entry<String, JsonNode>> fields = patchNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue() == null || field.getValue().isNull()) {
                result.remove(field.getKey());
            } else {
                result.set(field.getKey(), field.getValue());
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    private static ObjectNode parseObject(String json, String message) {
        if (json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException e) {
            // fall through
        }
        throw new ValidationException(message);
    }
}
