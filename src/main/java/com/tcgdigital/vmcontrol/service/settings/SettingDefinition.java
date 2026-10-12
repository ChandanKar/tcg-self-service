package com.tcgdigital.vmcontrol.service.settings;

import java.math.BigDecimal;

/**
 * One runtime setting (E29-T01). Tier 1 can be changed from the Settings page and takes effect
 * without a restart; tier 2 is shown read-only (it needs a restart or an .env change).
 * {@code fallback} is used when the property file has no value; min/max bound numbers.
 */
public record SettingDefinition(
        String key,
        Type type,
        int tier,
        String group,
        String label,
        String description,
        String fallback,
        BigDecimal min,
        BigDecimal max
) {
    public enum Type { BOOLEAN, INT, DECIMAL, STRING }

    public boolean editable() {
        return tier == 1;
    }

    static SettingDefinition flag(String group, String key, String label, String description, boolean fallback) {
        return new SettingDefinition(key, Type.BOOLEAN, 1, group, label, description, String.valueOf(fallback), null, null);
    }

    static SettingDefinition integer(String group, String key, String label, String description, long fallback, long min, long max) {
        return new SettingDefinition(key, Type.INT, 1, group, label, description, String.valueOf(fallback),
                BigDecimal.valueOf(min), BigDecimal.valueOf(max));
    }

    static SettingDefinition decimal(String group, String key, String label, String description, String fallback, String min, String max) {
        return new SettingDefinition(key, Type.DECIMAL, 1, group, label, description, fallback, new BigDecimal(min), new BigDecimal(max));
    }

    static SettingDefinition readOnly(String group, String key, String label, String description) {
        return new SettingDefinition(key, Type.STRING, 2, group, label, description, "", null, null);
    }
}
