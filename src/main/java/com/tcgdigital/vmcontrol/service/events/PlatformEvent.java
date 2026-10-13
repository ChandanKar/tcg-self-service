package com.tcgdigital.vmcontrol.service.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something that happened on the platform (E34-T06/T07), published with the application event
 * publisher; webhooks are queued for it after the publishing transaction commits.
 */
public record PlatformEvent(String id, PlatformEventType type, Instant occurredAt, String environmentId,
                            String environmentName, Map<String, Object> data) {

    public static PlatformEvent of(PlatformEventType type, String environmentId, String environmentName, Map<String, Object> data) {
        return new PlatformEvent(UUID.randomUUID().toString(), type, Instant.now(), environmentId, environmentName,
                data == null ? Map.of() : data);
    }
}
