package com.tcgdigital.vmcontrol.service.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks AWS resource ids in free text for people who may not see them (U01-T03): "The instance
 * i-0123456789abcdef0 is not in a state..." becomes "The instance i-… is not in a state...". The prefix
 * stays so the sentence still says what kind of resource it was. ARNs become "arn:…".
 *
 * <p>Stored text is never changed; this runs on the way out. AWS account numbers (12 digits) are left alone,
 * because they cannot be told apart from other numbers.
 */
public final class ProviderIdRedactor {

    private static final Pattern RESOURCE_ID = Pattern.compile(
            "\\b(i|vol|snap|eni|sg|subnet|vpc|ami|eipalloc|eipassoc|sir|lt|r|igw|nat|rtb|acl|fs|vpce)-(?:[0-9a-f]{17}|[0-9a-f]{8})\\b");
    private static final Pattern ARN = Pattern.compile("\\barn:aws[\\w-]*:[^\\s\"',)\\]]+");

    private ProviderIdRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutArns = ARN.matcher(text).replaceAll("arn:…");
        return RESOURCE_ID.matcher(withoutArns).replaceAll("$1-…");
    }

    /** Redacts every string inside maps and lists (webhook and tool payloads); other values are kept. */
    public static Object redactDeep(Object value) {
        if (value instanceof String s) {
            return redact(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, redactDeep(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(v -> copy.add(redactDeep(v)));
            return copy;
        }
        return value;
    }
}
