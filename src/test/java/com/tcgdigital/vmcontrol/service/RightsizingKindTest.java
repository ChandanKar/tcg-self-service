package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.RightsizingKind.Kind;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U15-T03: the kinds of change a rightsizing suggestion makes, from its direction and type names. */
class RightsizingKindTest {

    @Test
    void smallerNewerGravitonAndLargerAreReadFromTheTypes() {
        assertEquals(Set.of(Kind.SMALLER), RightsizingKind.of("SCALE_DOWN", "r6i.4xlarge", "r6i.2xlarge"));
        assertEquals(Set.of(Kind.SMALLER, Kind.NEWER), RightsizingKind.of("SCALE_DOWN", "m5.2xlarge", "m6a.xlarge"));
        assertEquals(Set.of(Kind.NEWER, Kind.GRAVITON), RightsizingKind.of("MODERNIZE", "c5.2xlarge", "c7g.2xlarge"));
        assertEquals(Set.of(Kind.NEWER), RightsizingKind.of("MODERNIZE", "m5.large", "m6a.large"));
        assertEquals(Set.of(Kind.LARGER), RightsizingKind.of("SCALE_UP", "t3.large", "t3.xlarge"));
        assertEquals(Set.of(), RightsizingKind.of("SCALE_DOWN", "r5.xlarge", null));
    }

    @Test
    void gravitonFamiliesCarryAGInTheirAttributeLetters() {
        for (String type : new String[] {"m6g.large", "c7gn.xlarge", "r6gd.2xlarge", "t4g.micro", "im4gn.large", "g5g.xlarge"}) {
            assertTrue(RightsizingKind.isGraviton(type), type);
        }
        for (String type : new String[] {"g4dn.xlarge", "m5.large", "m7i.large", "c6a.large", "x2iedn.xlarge", "nonsense", null}) {
            assertFalse(RightsizingKind.isGraviton(type), String.valueOf(type));
        }
        // an arm64 VM moving to a newer arm64 type is not a move to Graviton
        assertEquals(Set.of(Kind.NEWER), RightsizingKind.of("MODERNIZE", "m6g.large", "m7g.large"));
    }

    @Test
    void filtersMatchAKindAndTheAwsAgreement() {
        assertTrue(RightsizingKind.matches("graviton", "MODERNIZE", "c5.large", "c7g.large"));
        assertFalse(RightsizingKind.matches("SMALLER", "MODERNIZE", "c5.large", "c7g.large"));
        assertTrue(RightsizingKind.matches(null, "MODERNIZE", "c5.large", "c7g.large"));
        assertTrue(RightsizingKind.agreementMatches("AWS", "AGREE"));
        assertTrue(RightsizingKind.agreementMatches("AWS", "ONLY_AWS"));
        assertFalse(RightsizingKind.agreementMatches("AWS", "ONLY_RULE"));
        assertFalse(RightsizingKind.agreementMatches("AWS", "DISAGREE"));
        assertTrue(RightsizingKind.agreementMatches("", "DISAGREE"));
    }
}
