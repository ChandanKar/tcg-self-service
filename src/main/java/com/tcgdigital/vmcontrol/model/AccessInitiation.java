package com.tcgdigital.vmcontrol.model;

/**
 * How an access grant came to exist.
 *
 * <p>{@code DIRECT} — an admin / env-admin granted it without a request.
 * {@code REQUEST} — it was produced by approving an {@link EnvironmentAccessRequest};
 * {@code sourceRequestId} links back to that request.
 */
public enum AccessInitiation {
    DIRECT,
    REQUEST
}
