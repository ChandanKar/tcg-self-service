package com.tcgdigital.vmcontrol.service.settings;

import org.mockito.Answers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A SettingsService for unit tests (E29-T02, T03): answers from a mutable map, else the caller's
 * default, so a test can change a setting between two calls.
 */
public final class TestSettings {

    private TestSettings() {
    }

    public static SettingsService of(Map<String, String> values) {
        return Mockito.mock(SettingsService.class, invocation -> {
            String name = invocation.getMethod().getName();
            boolean lookup = name.startsWith("get") || name.equals("overrideOr");
            if (!lookup || invocation.getArguments().length != 2) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            String value = values.get((String) invocation.getArgument(0));
            Object fallback = invocation.getArgument(1);
            if (value == null) {
                return fallback;
            }
            if (name.equals("overrideOr")) { // typed by the current value it is given
                if (fallback instanceof Boolean) return Boolean.parseBoolean(value);
                if (fallback instanceof Integer) return Integer.parseInt(value);
                if (fallback instanceof Long) return Long.parseLong(value);
                if (fallback instanceof BigDecimal) return new BigDecimal(value);
                return value;
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
