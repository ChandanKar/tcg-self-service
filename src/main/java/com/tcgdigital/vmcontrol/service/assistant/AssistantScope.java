package com.tcgdigital.vmcontrol.service.assistant;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an assistant tool may show (E36-T02): admins see every active environment, everyone else
 * the environments they hold access to. A tool asking for another environment gets "not found",
 * never a hint that it exists.
 */
@Component
public class AssistantScope {

    /** No tool returns more rows than this. */
    public static final int MAX_ROWS = 50;

    private final EnvironmentService environmentService;
    private final SecurityService securityService;
    private final UserService userService;

    public AssistantScope(EnvironmentService environmentService, SecurityService securityService, UserService userService) {
        this.environmentService = environmentService;
        this.securityService = securityService;
        this.userService = userService;
    }

    public User user() {
        User user = userService.getCurrentUser();
        if (user == null) throw new IllegalArgumentException("Not signed in");
        return user;
    }

    public boolean isAdmin() {
        return user().isAdmin();
    }

    /** The environments the caller may see, by id, in name order. */
    public Map<String, Environment> viewable() {
        List<Environment> envs = isAdmin() ? environmentService.getAllActiveEnvironments() : environmentService.getEnvironmentsForCurrentUser();
        Map<String, Environment> byId = new LinkedHashMap<>();
        envs.stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).forEach(e -> byId.put(e.getEnvironmentId(), e));
        return byId;
    }

    /** One environment the caller may see, or "not found". */
    public Environment environment(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) throw new IllegalArgumentException("environmentId is required");
        Environment env = viewable().get(environmentId.trim());
        if (env == null || !securityService.canViewEnvironment(env.getEnvironmentId())) {
            throw new ResourceNotFoundException("Environment not found: " + environmentId);
        }
        return env;
    }

    /** A row limit: the asked one within 1..50, else the default. */
    public static int limit(Map<String, Object> args, int defaultLimit) {
        Object v = args.get("limit");
        int n = v instanceof Number num ? num.intValue() : defaultLimit;
        if (v instanceof String s && !s.isBlank()) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("limit must be a number");
            }
        }
        return Math.max(1, Math.min(MAX_ROWS, n));
    }

    public static String string(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    public static Integer integer(Map<String, Object> args, String key, int min, int max, int defaultValue) {
        Object v = args.get(key);
        int n;
        if (v == null || String.valueOf(v).isBlank()) n = defaultValue;
        else if (v instanceof Number num) n = num.intValue();
        else {
            try {
                n = Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a number");
            }
        }
        if (n < min || n > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return n;
    }

    /** Two decimals, as the tools report money. */
    public static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** A JSON schema property. */
    public static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    public static Map<String, Object> enumProp(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    public static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (required.length > 0) s.put("required", List.of(required));
        s.put("additionalProperties", false);
        return s;
    }
}
