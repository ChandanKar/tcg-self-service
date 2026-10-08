package com.tcgdigital.vmcontrol.exception;

/**
 * An Entra sign-in carried an email that already belongs to a user linked to a different Entra
 * account (oid). The existing row is not adopted: that would let a second directory account take
 * over the first one's access.
 */
public class AccountConflictException extends RuntimeException {

    private final String existingUserId;

    public AccountConflictException(String existingUserId) {
        super("This email belongs to a different directory account");
        this.existingUserId = existingUserId;
    }

    public String getExistingUserId() {
        return existingUserId;
    }
}
