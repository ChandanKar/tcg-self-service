package com.tcgdigital.vmcontrol.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * What an API token may do (E34-T01). A token never exceeds its owner: the role rules and
 * environment checks still apply to every call it makes.
 */
public enum ApiScope {
    ENVIRONMENTS_READ("environments:read"),
    OPERATIONS_WRITE("operations:write"),
    SCHEDULES_READ("schedules:read"),
    SCHEDULES_WRITE("schedules:write"),
    COST_READ("cost:read"),
    AUDIT_READ("audit:read"),
    ACCESS_WRITE("access:write"),
    ADMIN("admin");

    private final String value;

    ApiScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ApiScope> fromValue(String value) {
        return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst();
    }

    /** The scopes a user's role may put on a token: admin for admins only, access:write for admins and environment admins. */
    public static Set<ApiScope> allowedFor(User user) {
        EnumSet<ApiScope> allowed = EnumSet.allOf(ApiScope.class);
        if (!user.isAdmin()) {
            allowed.remove(ADMIN);
            if (!user.isEnvAdmin()) {
                allowed.remove(ACCESS_WRITE);
            }
        }
        return allowed;
    }
}
