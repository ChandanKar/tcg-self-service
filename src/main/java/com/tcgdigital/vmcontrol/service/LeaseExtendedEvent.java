package com.tcgdigital.vmcontrol.service;

import java.time.Instant;

/** An environment's lease was extended to {@code newEnd} by {@code userId} (E32-T05: a provisioned environment's destroy time follows). */
public record LeaseExtendedEvent(String environmentId, Instant newEnd, String userId) {
}
