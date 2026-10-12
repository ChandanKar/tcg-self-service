package com.tcgdigital.vmcontrol.dto;

/**
 * One notification type in a user's preferences (E29-T05). {@code inAppLocked}: the bell cannot be
 * turned off for it; {@code emailAvailable}: the platform sends email for it at all (off greys it out).
 */
public record NotificationPreferenceDTO(
        String type,
        String label,
        String category,
        boolean inApp,
        boolean email,
        boolean inAppLocked,
        boolean emailAvailable
) {
    /** A change sent by the My Account page; null leaves that channel unchanged. */
    public record Update(String type, Boolean inApp, Boolean email) {}
}
