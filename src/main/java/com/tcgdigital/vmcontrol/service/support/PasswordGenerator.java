package com.tcgdigital.vmcontrol.service.support;

import java.security.SecureRandom;

/**
 * Passwords the server makes for a person (U25-T02 Add people, U25-T04 admin reset): 16 characters
 * from a cryptographically secure source, drawn from the look-alike-free alphabet the Add people page
 * uses (no I, O, l, o, 0 or 1). A generated password is shown once and must be changed at the
 * person's next sign-in; it is never logged, audited or stored in the clear.
 */
public final class PasswordGenerator {

    /** The same characters as add-people.js PASSWORD_CHARS. */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789-_.!";
    public static final int LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {
    }

    public static String generate() {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
