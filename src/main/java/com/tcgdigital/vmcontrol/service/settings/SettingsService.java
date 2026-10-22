package com.tcgdigital.vmcontrol.service.settings;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AppSetting;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.repository.AppSettingRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime settings (E29-T01): an admin's override in app_setting, else the application
 * property, else the caller's default. Values are cached for 30 s, so every instance sees a
 * change within that time; the instance that writes forgets its cache at once. Only tier 1
 * keys can be written, after type and range checks, and every write is audited (the key as the
 * target name: keys are longer than audit_log.target_id).
 */
@Service
public class SettingsService {

    static final Duration TTL = Duration.ofSeconds(30);

    public enum Source { DEFAULT, OVERRIDE }

    /** A setting as the admin page shows it. */
    public record SettingView(SettingDefinition definition, String value, String defaultValue, Source source,
                              java.sql.Timestamp updatedAt, String updatedByUserId) {}

    private record Cached(Optional<String> value, Instant loadedAt) {}

    private final AppSettingRepository repository;
    private final Environment environment;
    private final AuditService auditService;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    Clock clock = Clock.systemUTC();

    public SettingsService(AppSettingRepository repository, Environment environment, AuditService auditService) {
        this.repository = repository;
        this.environment = environment;
        this.auditService = auditService;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return raw(key).map(v -> Boolean.parseBoolean(v.trim())).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return raw(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return raw(key).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        return raw(key).map(v -> {
            try {
                return new BigDecimal(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public String getString(String key, String defaultValue) {
        return raw(key).orElse(defaultValue);
    }

    // ---- an admin's override, else the caller's current value (E29-T03) ----
    // For code that keeps its @Value field as the deploy-time value: without an override the
    // behaviour is exactly the field's.

    public boolean overrideOr(String key, boolean current) {
        return override(key).map(v -> Boolean.parseBoolean(v.trim())).orElse(current);
    }

    public int overrideOr(String key, int current) {
        return override(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return current;
            }
        }).orElse(current);
    }

    public long overrideOr(String key, long current) {
        return override(key).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return current;
            }
        }).orElse(current);
    }

    public BigDecimal overrideOr(String key, BigDecimal current) {
        return override(key).map(v -> {
            try {
                return new BigDecimal(v.trim());
            } catch (NumberFormatException e) {
                return current;
            }
        }).orElse(current);
    }

    private final Map<String, Cached> overrideCache = new ConcurrentHashMap<>();

    /** Only the stored override, cached like every read. */
    Optional<String> override(String key) {
        Instant now = clock.instant();
        Cached cached = overrideCache.get(key);
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(now)) {
            return cached.value();
        }
        Optional<String> value = repository.findById(key).map(AppSetting::getSettingValue);
        overrideCache.put(key, new Cached(value, now));
        return value;
    }

    /** The effective value: override, else property; empty when neither is set. */
    Optional<String> raw(String key) {
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(now)) {
            return cached.value();
        }
        Optional<String> value = repository.findById(key).map(AppSetting::getSettingValue)
                .or(() -> Optional.ofNullable(environment.getProperty(key)).filter(v -> !v.isEmpty()));
        cache.put(key, new Cached(value, now));
        return value;
    }

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        Map<String, AppSetting> overrides = new java.util.HashMap<>();
        repository.findAll().forEach(s -> overrides.put(s.getSettingKey(), s));
        // The Settings page's sections in order; within one, the registry's order (U14-T06).
        return SettingsRegistry.DEFINITIONS.stream()
                .sorted(java.util.Comparator.comparingInt(d -> SettingsRegistry.SECTIONS.indexOf(d.group())))
                .map(d -> {
            String fromProperties = environment.getProperty(d.key());
            String defaultValue = fromProperties != null && !fromProperties.isEmpty() ? fromProperties : d.fallback();
            AppSetting override = d.editable() ? overrides.get(d.key()) : null;
            return override == null ? new SettingView(d, defaultValue, defaultValue, Source.DEFAULT, null, null)
                    : new SettingView(d, override.getSettingValue(), defaultValue, Source.OVERRIDE,
                    override.getUpdatedAt(), override.getUpdatedByUserId());
        }).toList();
    }

    @Transactional
    public SettingView set(String key, String value, String actorId) {
        SettingDefinition definition = editable(key);
        String normalized = validate(definition, value);
        String old = currentValue(definition);
        AppSetting row = repository.findById(key).orElseGet(() -> new AppSetting(key, normalized, actorId));
        row.setSettingValue(normalized);
        row.setUpdatedByUserId(actorId);
        repository.save(row);
        cache.remove(key);
        overrideCache.remove(key);
        auditService.logAction(actorId, AuditAction.SYSTEM_CONFIG_UPDATED, "setting", null, key,
                key + ": " + old + " -> " + normalized);
        return view(definition);
    }

    /** One change of a batch save. */
    public record Change(String key, String value) {
    }

    /**
     * Saves a section's changes together (U14-T06): every change is checked first, and one unknown,
     * read-only or out-of-range value refuses them all, naming each. Then all are written in this
     * transaction and audited one by one.
     */
    @Transactional
    public List<SettingView> updateAll(List<Change> changes, String actorId) {
        if (changes == null || changes.isEmpty()) {
            throw new ValidationException("No changes to save");
        }
        java.util.Map<String, String> normalized = new java.util.LinkedHashMap<>();
        List<String> problems = new java.util.ArrayList<>();
        for (Change c : changes) {
            String key = c == null ? null : c.key();
            if (key == null || key.isBlank()) {
                problems.add("a change without a key");
                continue;
            }
            if (normalized.containsKey(key)) {
                problems.add(key + " is listed twice");
                continue;
            }
            java.util.Optional<SettingDefinition> definition = SettingsRegistry.find(key);
            if (definition.isEmpty()) {
                problems.add(key + " is not a setting");
            } else if (!definition.get().editable()) {
                problems.add(key + " is read-only: change it in the server settings and restart");
            } else {
                try {
                    normalized.put(key, validate(definition.get(), c.value()));
                } catch (ValidationException e) {
                    problems.add(e.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("Nothing was saved: " + String.join("; ", problems));
        }
        return normalized.entrySet().stream().map(e -> set(e.getKey(), e.getValue(), actorId)).toList();
    }

    @Transactional
    public SettingView reset(String key, String actorId) {
        SettingDefinition definition = editable(key);
        if (repository.existsById(key)) {
            String old = currentValue(definition);
            repository.deleteById(key);
            cache.remove(key);
            overrideCache.remove(key);
            auditService.logAction(actorId, AuditAction.SYSTEM_CONFIG_UPDATED, "setting", null, key,
                    key + ": " + old + " -> default (" + defaultValue(definition) + ")");
        }
        return view(definition);
    }

    private SettingDefinition editable(String key) {
        SettingDefinition definition = SettingsRegistry.find(key)
                .orElseThrow(() -> new ResourceNotFoundException("Setting", key));
        if (!definition.editable()) {
            throw new ValidationException("'" + key + "' is a read-only setting: change it in .env and restart");
        }
        return definition;
    }

    /** The value in canonical form, or a 400 when it does not fit the type or range. */
    static String validate(SettingDefinition definition, String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("A value is required for " + definition.key());
        }
        String v = value.trim();
        switch (definition.type()) {
            case BOOLEAN -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new ValidationException(definition.key() + " must be true or false");
                }
                return v.toLowerCase();
            }
            case INT, DECIMAL -> {
                BigDecimal number;
                try {
                    number = new BigDecimal(v);
                } catch (NumberFormatException e) {
                    throw new ValidationException(definition.key() + " must be a number");
                }
                if (definition.type() == SettingDefinition.Type.INT && number.stripTrailingZeros().scale() > 0) {
                    throw new ValidationException(definition.key() + " must be a whole number");
                }
                if (definition.min() != null && number.compareTo(definition.min()) < 0
                        || definition.max() != null && number.compareTo(definition.max()) > 0) {
                    throw new ValidationException(definition.key() + " must be between " + definition.min().toPlainString()
                            + " and " + definition.max().toPlainString());
                }
                return definition.type() == SettingDefinition.Type.INT ? number.toBigInteger().toString() : number.toPlainString();
            }
            case REGIONS -> {
                java.util.List<String> codes = new java.util.ArrayList<>();
                for (String part : v.split(",")) {
                    String code = part.trim().toLowerCase(java.util.Locale.ROOT);
                    if (code.isEmpty() || codes.contains(code)) {
                        continue;
                    }
                    if (!REGION_CODE.matcher(code).matches()) {
                        throw new ValidationException(definition.key() + ": '" + part.trim() + "' is not an AWS region code, such as eu-west-1");
                    }
                    codes.add(code);
                }
                if (codes.isEmpty()) {
                    throw new ValidationException("A value is required for " + definition.key());
                }
                return String.join(",", codes);
            }
            default -> {
                return v;
            }
        }
    }

    /** An AWS region code: eu-west-1, ap-southeast-5, us-gov-west-1, me-central-1. */
    private static final java.util.regex.Pattern REGION_CODE = java.util.regex.Pattern.compile("^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$");

    private String defaultValue(SettingDefinition d) {
        String fromProperties = environment.getProperty(d.key());
        return fromProperties != null && !fromProperties.isEmpty() ? fromProperties : d.fallback();
    }

    private String currentValue(SettingDefinition d) {
        return repository.findById(d.key()).map(AppSetting::getSettingValue).orElseGet(() -> defaultValue(d));
    }

    private SettingView view(SettingDefinition d) {
        Optional<AppSetting> override = repository.findById(d.key());
        return override.map(o -> new SettingView(d, o.getSettingValue(), defaultValue(d), Source.OVERRIDE,
                        o.getUpdatedAt() != null ? o.getUpdatedAt() : new java.sql.Timestamp(clock.millis()), o.getUpdatedByUserId()))
                .orElseGet(() -> new SettingView(d, defaultValue(d), defaultValue(d), Source.DEFAULT, null, null));
    }
}
