package com.tcgdigital.vmcontrol.exception;

/** An AWS account that is unknown or deactivated was asked for (E28-T04); answered as a 400. */
public class AwsAccountUnavailableException extends ValidationException {

    private final String accountRef;

    public AwsAccountUnavailableException(String accountRef) {
        super("AWS account " + accountRef + " is unknown or inactive");
        this.accountRef = accountRef;
    }

    public String getAccountRef() {
        return accountRef;
    }
}
