package com.tcgdigital.vmcontrol.service.settings;

import org.mockito.Answers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A SettingsService for unit tests (E29-T02): answers from a mutable map, else the caller's
 * default, so a test can change a setting between two calls.
 */
public final class TestSettings {

    private TestSettings() {
    }

    public static SettingsService of(Map<String, String> values) {
        return Mockito.mock(SettingsService.class, invocation -> {
            String name = invocation.getMethod().getName();
            if (!name.startsWith("get") || invocation.getArguments().length != 2) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            String value = values.get((String) invocation.getArgument(0));
            Object fallback = invocation.getArgument(1);
            if (value == null) {
                return fallback;
            }
            return switch (name) {
                case "getBoolean" -> Boolean.parseBoolean(value);
                case "getInt" -> Integer.parseInt(value);
                case "getLong" -> Long.parseLong(value);
                case "getDecimal" -> new BigDecimal(value);
                default -> value;
            };
        });
    }
}
