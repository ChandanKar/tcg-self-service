package com.tcgdigital.vmcontrol.service.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameSanitizerTest {

    @Test
    void removesMarkupCharacters() {
        assertThat(NameSanitizer.clean("<img src=x onerror=alert(1)>")).isEqualTo("img src=x onerror=alert(1)");
        assertThat(NameSanitizer.clean("`web`")).isEqualTo("web");
    }

    @Test
    void keepsApostrophesQuotesAndAmpersands() {
        assertThat(NameSanitizer.clean("O'Brien \"prod\" & Co")).isEqualTo("O'Brien \"prod\" & Co");
    }

    @Test
    void controlCharactersBecomeSpacesAndWhitespaceCollapses() {
        assertThat(NameSanitizer.clean("  web\t01\n\u0007prod  ")).isEqualTo("web 01 prod");
    }

    @Test
    void lengthIsCapped() {
        assertThat(NameSanitizer.clean("a".repeat(300))).hasSize(NameSanitizer.MAX_LENGTH);
    }

    @Test
    void nullAndEmptyResultsBecomeNull() {
        assertThat(NameSanitizer.clean(null)).isNull();
        assertThat(NameSanitizer.clean("  <>  ")).isNull();
    }

    @Test
    void validationPatternMatchesTheSanitizer() {
        assertThat("O'Brien & Co").matches(NameSanitizer.SAFE_NAME_REGEX);
        assertThat("web<b>").doesNotMatch(NameSanitizer.SAFE_NAME_REGEX);
        assertThat("web`x").doesNotMatch(NameSanitizer.SAFE_NAME_REGEX);
        assertThat("web\u0000x").doesNotMatch(NameSanitizer.SAFE_NAME_REGEX);
        assertThat(NameSanitizer.clean("web<b>`x")).matches(NameSanitizer.SAFE_NAME_REGEX);
    }
}
