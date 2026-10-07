package com.tcgdigital.vmcontrol.service.support;

import java.util.regex.Pattern;

/**
 * Keeps markup characters out of environment, group and VM names (finding C3).
 *
 * <p>Names typed by users are rejected with {@link #SAFE_NAME_REGEX} on the request DTOs; names
 * taken from cloud tags (EC2 {@code Name}) cannot be rejected, so they are cleaned with
 * {@link #clean(String)} before they are stored. Apostrophes, quotes and ampersands are kept:
 * they are legitimate in names and the frontend escapes them.
 */
public final class NameSanitizer {

    /** Bean Validation pattern: no {@code < > `} and no control characters. */
    public static final String SAFE_NAME_REGEX = "^[^<>`\\p{Cntrl}]*$";

    public static final String SAFE_NAME_MESSAGE = "Name must not contain < > ` or control characters";

    public static final int MAX_LENGTH = 255;

    private static final Pattern UNSAFE = Pattern.compile("[<>`\\p{Cntrl}]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private NameSanitizer() {
    }

    /**
     * Removes {@code < > `} and control characters, collapses whitespace, trims and caps the
     * length at {@value #MAX_LENGTH}. Returns {@code null} for {@code null} or for a value that
     * is empty once cleaned.
     */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        // Control characters (tabs, newlines) become spaces first so words do not run together.
        String cleaned = WHITESPACE_RUN.matcher(UNSAFE.matcher(value.replaceAll("[\\t\\n\\r]", " ")).replaceAll(""))
                .replaceAll(" ")
                .trim();
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH).trim();
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
