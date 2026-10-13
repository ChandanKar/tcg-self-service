package com.tcgdigital.vmcontrol.service.events;

import java.util.Arrays;
import java.util.Optional;

/** The events webhooks can subscribe to (E34-T06/T07); {@code ping} is only sent by a subscription's test. */
public enum PlatformEventType {
    OPERATION_COMPLETED("operation.completed"),
    OPERATION_FAILED("operation.failed"),
    ENVIRONMENT_STARTED("environment.started"),
    ENVIRONMENT_STOPPED("environment.stopped"),
    LEASE_EXPIRING("lease.expiring"),
    LEASE_EXPIRED("lease.expired"),
    ACCESS_GRANTED("access.granted"),
    ACCESS_REVOKED("access.revoked"),
    IDLE_STOPPED("idle.stopped"),
    BUDGET_THRESHOLD("budget.threshold"),
    PROVISIONING_READY("provisioning.ready"),
    PROVISIONING_DESTROYED("provisioning.destroyed"),
    PING("ping");

    private final String value;

    PlatformEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<PlatformEventType> fromValue(String value) {
        return Arrays.stream(values()).filter(t -> t.value.equals(value)).findFirst();
    }
}
