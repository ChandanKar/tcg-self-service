package com.tcgdigital.vmcontrol.service.support;

import java.util.Objects;

/**
 * The fields an edit changed, for an audit row's details (E11-T03): "field: old -> new; ...",
 * at most 500 characters. Callers never pass raw metadata JSON.
 */
public final class AuditChanges {

    private static final int MAX_LENGTH = 500;

    private final StringBuilder text = new StringBuilder();

    /** Record a field if its value changed. */
    public AuditChanges add(String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(field).append(": ").append(before).append(" -> ").append(after);
        }
        return this;
    }

    /** Record that a field changed without showing its values (e.g. metadata). */
    public AuditChanges changed(String field, boolean changed) {
        if (changed) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(field).append(" changed");
        }
        return this;
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public String toString() {
        return text.length() <= MAX_LENGTH ? text.toString() : text.substring(0, MAX_LENGTH - 1) + "…";
    }
}
