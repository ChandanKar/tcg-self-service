package com.tcgdigital.vmcontrol.service.aws;

import java.util.Locale;

/**
 * The last access check's error in a few plain words (U14-T03, AwsAccounts.dc.html): "Role can't be
 * assumed" for AWS's AccessDenied on sts:AssumeRole. The raw message stays on the account for the
 * tooltip; this is what the Last check column says.
 */
public final class AwsAccountErrors {

    private AwsAccountErrors() {
    }

    public static String summary(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return null;
        }
        String e = lastError.toLowerCase(Locale.ROOT);
        if (e.startsWith("role resolved to account")) {
            return "The role is in a different account";
        }
        if (e.contains("assumerole") || (e.contains("accessdenied") || e.contains("access denied")) && e.contains("role")) {
            return "Role can't be assumed";
        }
        if (e.contains("expiredtoken") || e.contains("token has expired") || e.contains("expired token")) {
            return "The platform's credentials have expired";
        }
        if (e.contains("invalidclienttokenid") || e.contains("unrecognizedclient") || e.contains("signaturedoesnotmatch")
                || e.contains("security token included in the request is invalid")) {
            return "The platform's credentials are not valid";
        }
        if (e.contains("unable to load credentials") || e.contains("no credentials")) {
            return "No AWS credentials on the server";
        }
        if (e.contains("unable to execute http request") || e.contains("unknownhost") || e.contains("timed out")
                || e.contains("connect")) {
            return "AWS could not be reached";
        }
        if (e.contains("accessdenied") || e.contains("access denied") || e.contains("not authorized")) {
            return "Access denied by AWS";
        }
        return "Access check failed";
    }
}
