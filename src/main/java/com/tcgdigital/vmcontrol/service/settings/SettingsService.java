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
    public record SettingView(SettingDefinition definition, String value, String defaultValue, Source source) {}

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
        Map<String, String> overrides = new java.util.HashMap<>();
        repository.findAll().forEach(s -> overrides.put(s.getSettingKey(), s.getSettingValue()));
        return SettingsRegistry.DEFINITIONS.stream().map(d -> {
            String fromProperties = environment.getProperty(d.key());
            String defaultValue = fromProperties != null && !fromProperties.isEmpty() ? fromProperties : d.fallback();
            String override = d.editable() ? overrides.get(d.key()) : null;
            return new SettingView(d, override != null ? override : defaultValue, defaultValue,
                    override != null ? Source.OVERRIDE : Source.DEFAULT);
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
        auditService.logAction(actorId, AuditAction.SYSTEM_CONFIG_UPDATED, "setting", null, key,
                key + ": " + old + " -> " + normalized);
        return view(definition);
    }

    @Transactional
    public SettingView reset(String key, String actorId) {
        SettingDefinition definition = editable(key);
        if (repository.existsById(key)) {
            String old = currentValue(definition);
            repository.deleteById(key);
            cache.remove(key);
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
            default -> {
                return v;
            }
        }
    }

    private String defaultValue(SettingDefinition d) {
        String fromProperties = environment.getProperty(d.key());
        return fromProperties != null && !fromProperties.isEmpty() ? fromProperties : d.fallback();
    }

    private String currentValue(SettingDefinition d) {
        return repository.findById(d.key()).map(AppSetting::getSettingValue).orElseGet(() -> defaultValue(d));
    }

    private SettingView view(SettingDefinition d) {
        Optional<String> override = repository.findById(d.key()).map(AppSetting::getSettingValue);
        return new SettingView(d, override.orElseGet(() -> defaultValue(d)), defaultValue(d),
                override.isPresent() ? Source.OVERRIDE : Source.DEFAULT);
    }
}
