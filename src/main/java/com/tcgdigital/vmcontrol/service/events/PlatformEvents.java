package com.tcgdigital.vmcontrol.service.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes {@link PlatformEvent}s (E34-T07) where the platform already tells people about
 * something. Listeners act after the publishing transaction commits; publishing never fails the
 * business action.
 */
@Component
public class PlatformEvents {

    private static final Logger log = LoggerFactory.getLogger(PlatformEvents.class);

    private final ApplicationEventPublisher publisher;

    public PlatformEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(PlatformEventType type, String environmentId, String environmentName, Map<String, Object> data) {
        try {
            publisher.publishEvent(PlatformEvent.of(type, environmentId, environmentName, data));
        } catch (Exception e) {
            log.warn("Could not publish {} for environment {}: {}", type.value(), environmentId, e.getMessage());
        }
    }
}
