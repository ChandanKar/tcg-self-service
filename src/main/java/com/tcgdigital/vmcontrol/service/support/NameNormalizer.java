package com.tcgdigital.vmcontrol.service.support;

/**
 * The form names are stored in (environment, group and VM names): trimmed, lower case, spaces
 * as hyphens. Duplicate checks must run on this form, because the unique indexes are on it
 * ('My Env' next to 'my-env' was a 500, M3).
 */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    public static String slug(String raw) {
        return raw == null ? null : raw.trim().toLowerCase().replaceAll("\\s+", "-");
    }
}
