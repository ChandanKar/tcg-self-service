package com.tcgdigital.vmcontrol.service.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** U01-T06: an untagged VM gets a stable placeholder name that does not reveal its instance id. */
class UnnamedVmNamesTest {

    private static final String ID = "i-0123456789abcdef0";

    @Test
    void thePlaceholderIsStableAndHidesTheId() {
        String name = UnnamedVmNames.placeholderFor(ID);

        assertThat(name).matches("unnamed-[0-9a-f]{6}").isEqualTo(UnnamedVmNames.placeholderFor(ID));
        assertThat(name).doesNotContain("0123").doesNotContain("cdef");
        assertThat(UnnamedVmNames.placeholderFor("i-0fedcba9876543210")).isNotEqualTo(name);
    }

    @Test
    void theSuffixDoesNotQuoteTheId() {
        assertThat(UnnamedVmNames.suffixFor(ID)).hasSize(4).isNotEqualTo("def0");
    }

    @Test
    void aVmIsStillUnnamedWhileItCarriesThePlaceholderOrTheId() {
        assertThat(UnnamedVmNames.isPlaceholder(UnnamedVmNames.placeholderFor(ID), ID)).isTrue();
        assertThat(UnnamedVmNames.isPlaceholder(ID.toUpperCase(), ID)).isTrue();
        assertThat(UnnamedVmNames.isPlaceholder("web-01", ID)).isFalse();
        assertThat(UnnamedVmNames.isPlaceholder(null, ID)).isFalse();
    }
}
