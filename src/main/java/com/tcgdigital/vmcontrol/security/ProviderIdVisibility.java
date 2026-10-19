package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.service.UserService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Who may see cloud provider identifiers (U01-T01): AWS instance ids, volume ids and similar are shown
 * to administrators (role ADMIN) only, everywhere. IP addresses are not covered by this rule.
 *
 * <p>The answer comes from the database user behind the current request, so it holds for Entra,
 * password, API-token and dev (X-User-Id) sign-ins alike. It is memoised per request. Without a
 * request (schedulers, async workers, webhook threads) or without a signed-in user it is {@code false}:
 * the rule fails closed.
 *
 * <p>Used through {@link #currentViewerMaySee()} by the {@code @AdminOnly} JSON serializer, which may
 * run where no Spring injection is available.
 */
@Component
public class ProviderIdVisibility {

    private static final String MEMO = ProviderIdVisibility.class.getName() + ".admin";

    private static volatile ProviderIdVisibility instance;

    private final UserService userService;

    public ProviderIdVisibility(UserService userService) {
        this.userService = userService;
    }

    @PostConstruct
    void register() {
        install(this);
    }

    /** Makes {@code visibility} the one {@link #currentViewerMaySee()} asks; returns the previous one (tests restore it). */
    static ProviderIdVisibility install(ProviderIdVisibility visibility) {
        ProviderIdVisibility previous = instance;
        instance = visibility;
        return previous;
    }

    /** True when the user behind the current request is an administrator; false everywhere else. */
    public static boolean currentViewerMaySee() {
        ProviderIdVisibility visibility = instance;
        return visibility != null && visibility.viewerMaySeeProviderIds();
    }

    public boolean viewerMaySeeProviderIds() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        Object memo = attributes.getAttribute(MEMO, RequestAttributes.SCOPE_REQUEST);
        if (memo instanceof Boolean admin) {
            return admin;
        }
        boolean admin;
        try {
            admin = userService.isCurrentUserAdmin();
        } catch (RuntimeException e) {
            admin = false;
        }
        attributes.setAttribute(MEMO, admin, RequestAttributes.SCOPE_REQUEST);
        return admin;
    }
}
