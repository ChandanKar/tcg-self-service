package com.tcgdigital.vmcontrol.service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kinds of change a rightsizing suggestion makes (U15-T03), read from its direction and the two
 * instance type names: a smaller or larger size, a newer generation, a move to Graviton (ARM). One
 * suggestion can be several (m5.2xlarge to m6a.xlarge is smaller and newer). Mirrored for the row
 * labels by static/js/features/rightsizing-kind.js.
 */
public final class RightsizingKind {

    public enum Kind { SMALLER, LARGER, NEWER, GRAVITON }

    /** "m6gd.large": class letters, generation digits, attribute letters, size. */
    private static final Pattern TYPE = Pattern.compile("^([a-z]+)(\\d+)([a-z-]*)\\.(.+)$");

    private RightsizingKind() {
    }

    /** The kinds of one suggestion; empty without a suggested type. */
    public static Set<Kind> of(String direction, String currentType, String suggestedType) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        if (suggestedType == null || suggestedType.isBlank()) {
            return kinds;
        }
        if ("SCALE_DOWN".equals(direction)) {
            kinds.add(Kind.SMALLER);
        } else if ("SCALE_UP".equals(direction)) {
            kinds.add(Kind.LARGER);
        }
        int from = generation(currentType);
        int to = generation(suggestedType);
        if ("MODERNIZE".equals(direction) || (from > 0 && to > from)) {
            kinds.add(Kind.NEWER);
        }
        if (isGraviton(suggestedType) && !isGraviton(currentType)) {
            kinds.add(Kind.GRAVITON);
        }
        return kinds;
    }

    /** Whether a suggestion is of the given kind (SMALLER, LARGER, NEWER or GRAVITON); a blank kind matches all. */
    public static boolean matches(String kind, String direction, String currentType, String suggestedType) {
        if (kind == null || kind.isBlank()) {
            return true;
        }
        return of(direction, currentType, suggestedType).contains(Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
    }

    /** A Graviton family carries a "g" in its attribute letters: m6g, c7gn, r6gd, t4g, im4gn. */
    public static boolean isGraviton(String type) {
        Matcher m = type == null ? null : TYPE.matcher(type.toLowerCase(Locale.ROOT));
        return m != null && m.matches() && m.group(3).contains("g");
    }

    /** The generation digits of a type (m5.large is 5), or 0 when the name is not an EC2 type. */
    public static int generation(String type) {
        Matcher m = type == null ? null : TYPE.matcher(type.toLowerCase(Locale.ROOT));
        return m != null && m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /** "AWS": AWS Compute Optimizer agrees with the suggestion or made it; a blank filter matches all. */
    public static boolean agreementMatches(String agreement, String rowAgreement) {
        if (agreement == null || agreement.isBlank()) {
            return true;
        }
        return "AGREE".equals(rowAgreement) || "ONLY_AWS".equals(rowAgreement);
    }
}
