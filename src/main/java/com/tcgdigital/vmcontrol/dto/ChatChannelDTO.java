package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;
import java.util.List;

/**
 * A chat channel as the API shows it (E24-T02). Never carries the webhook URL: only a masked host.
 */
public record ChatChannelDTO(
        String channelId,
        String environmentId,
        String provider,
        String label,
        /** e.g. "…westeurope.logic.azure.com". */
        String maskedHost,
        List<String> eventTypes,
        boolean active,
        String createdBy,
        Timestamp createdAt,
        Timestamp updatedAt) {

    /** Create/update body. On update a null webhookUrl keeps the stored one. */
    public record Request(String environmentId, String provider, String label, String webhookUrl,
                          List<String> eventTypes, Boolean active) {}

    /** Result of a test message: no URL, only the kind of failure. */
    public record TestResult(boolean delivered, String error) {}

    /** What the channel editor needs. */
    public record Options(boolean enabled, List<String> providers, List<String> eventTypes) {}
}
