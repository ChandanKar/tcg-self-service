package com.tcgdigital.vmcontrol.exception;

/**
 * Raised by admin onboarding when an {@code app_user} row already exists for the target
 * identity (matched by Entra {@code oid} or by email). Maps to {@code 409}; the response
 * carries the existing {@code userId} so the panel can select that user (or offer to
 * reactivate, when {@link #isActive()} is false) instead.
 */
public class UserAlreadyExistsException extends RuntimeException {

    private final String userId;
    private final boolean active;

    public UserAlreadyExistsException(String userId, boolean active) {
        super(active
                ? "A user with this identity already exists."
                : "A deactivated user with this identity already exists — reactivate them instead.");
        this.userId = userId;
        this.active = active;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isActive() {
        return active;
    }
}
