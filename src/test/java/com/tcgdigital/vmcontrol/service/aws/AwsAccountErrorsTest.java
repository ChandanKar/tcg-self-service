package com.tcgdigital.vmcontrol.service.aws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** U14-T03: the Last check column says what went wrong in plain words; AWS's message stays for the tooltip. */
class AwsAccountErrorsTest {

    @Test
    void awsErrorsBecomePlainWords() {
        assertThat(AwsAccountErrors.summary("User: arn:aws:sts::123:assumed-role/x is not authorized to perform: sts:AssumeRole on resource: "
                + "arn:aws:iam::456:role/VmControlAccess (Service: Sts, Status Code: 403, Request ID: abc)")).isEqualTo("Role can't be assumed");
        assertThat(AwsAccountErrors.summary("AccessDenied: not authorized to perform sts:AssumeRole")).isEqualTo("Role can't be assumed");
        assertThat(AwsAccountErrors.summary("Role resolved to account 999999999999")).isEqualTo("The role is in a different account");
        assertThat(AwsAccountErrors.summary("The security token included in the request is expired (Service: Sts, ExpiredToken)"))
                .isEqualTo("The platform's credentials have expired");
        assertThat(AwsAccountErrors.summary("The security token included in the request is invalid. (Service: Sts, Status Code: 403)"))
                .isEqualTo("The platform's credentials are not valid");
        assertThat(AwsAccountErrors.summary("Unable to load credentials from any of the providers in the chain"))
                .isEqualTo("No AWS credentials on the server");
        assertThat(AwsAccountErrors.summary("Unable to execute HTTP request: sts.amazonaws.com")).isEqualTo("AWS could not be reached");
        assertThat(AwsAccountErrors.summary("Something else entirely")).isEqualTo("Access check failed");
        assertThat(AwsAccountErrors.summary(null)).isNull();
        assertThat(AwsAccountErrors.summary(" ")).isNull();
    }
}
