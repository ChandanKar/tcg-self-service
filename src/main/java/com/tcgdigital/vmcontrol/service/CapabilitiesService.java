package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CapabilitiesDTO;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds {@link CapabilitiesDTO} (U02-T01): the person's role plus the on/off state of each optional
 * feature, read through SettingsService so a runtime override counts as soon as it is saved.
 */
@Service
public class CapabilitiesService {

    /** Feature key sent to the app, the property (and setting) that switches it, and its default. */
    private record Switch(String feature, String property, boolean defaultValue) {
    }

    private static final Switch[] SWITCHES = {
            new Switch("templates", "provisioning.enabled", false),
            new Switch("approvals", "approvals.enabled", false),
            new Switch("webhooks", "webhooks.enabled", false),
            new Switch("chat", "chat.enabled", false),
            new Switch("spot", "spot.enabled", false),
            new Switch("carbon", "carbon.enabled", false),
            new Switch("rds", "rds.enabled", false),
            new Switch("rdsKeepStopped", "rds.keep-stopped.enabled", false),
            new Switch("leases", "lease.enabled", false),
            new Switch("idleStop", "automation.idle-stop.enabled", false),
            new Switch("apiTokens", "api-tokens.enabled", false),
            new Switch("assistant", "assistant.chat.enabled", false),
            new Switch("jira", "jira.enabled", false),
            new Switch("budgetActions", "budgets.actions.enabled", false),
            new Switch("quotas", "quotas.enabled", false),
            new Switch("awsOrganizations", "aws.organizations.enabled", false),
            // U13-T05: Add people searches the Microsoft directory only when lookup is on.
            new Switch("directory", "graph.directory.enabled", false),
            // U15-T01: Cost management's Data actions hide what is switched off.
            new Switch("costTagging", "cost.tagging.enabled", false),
            new Switch("costActuals", "cost.actuals.enabled", false),
            // U15-T03: the Rightsizing tab's Resize safely… and its Spot section.
            new Switch("safeResize", "rightsizing.safe-resize.enabled", false),
            new Switch("spotPricing", "spot.pricing.enabled", false),
            // U15-T04: the Commitments tab shows only while RI / Savings Plan coverage is on.
            new Switch("costReservations", "cost.reservations.enabled", false),
            // U22-T07: pages of switched-off features say so instead of looking live.
            new Switch("chargeback", "chargeback.enabled", false),
            new Switch("costAnomalies", "cost.anomalies.enabled", false),
            new Switch("wasteSweep", "waste.sweep.enabled", false),
            // U22-T08: pages promise an email only when email is on.
            new Switch("email", "notification.email.enabled", false)
    };

    private final UserService userService;
    private final SettingsService settings;

    public CapabilitiesService(UserService userService, SettingsService settings) {
        this.userService = userService;
        this.settings = settings;
    }

    public CapabilitiesDTO forCurrentUser() {
        User user = userService.getCurrentUser();
        boolean admin = user != null && user.isAdmin();
        boolean envAdmin = user != null && (user.isAdmin() || user.isEnvAdmin());
        Map<String, Boolean> features = new LinkedHashMap<>();
        for (Switch s : SWITCHES) {
            features.put(s.feature(), settings.getBoolean(s.property(), s.defaultValue()));
        }
        // U13-T09: Add people says whether each person gets an email.
        features.put("onboardingEmail", settings.getBoolean("notification.email.enabled", false)
                && settings.getBoolean("notification.email.onboarded.enabled", false));
        return new CapabilitiesDTO(admin, envAdmin, features);
    }
}
