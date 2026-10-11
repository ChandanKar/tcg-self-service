package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Accounts chosen from the organization to register (E28-T08), and what happened to each. */
public final class ImportAwsAccountsDTO {

    private ImportAwsAccountsDTO() {
    }

    public record Request(@NotEmpty @Valid List<Item> accounts) {}

    public record Item(
            @NotBlank @Pattern(regexp = "^\\d{12}$") String accountNumber,
            @NotBlank @Size(max = 255) String displayName,
            @NotBlank @Pattern(regexp = "^arn:aws(-[a-z]+)?:iam::\\d{12}:role/[\\w+=,.@/-]+$", message = "must be an IAM role ARN")
            String roleArn,
            @Size(min = 2, max = 1224) String externalId,
            List<@Pattern(regexp = "^[a-z]{2}(-[a-z]+)+-\\d$") String> regions
    ) {}

    public record Skipped(String accountNumber, String reason) {}

    public record Result(List<AwsAccountDTO> created, List<Skipped> skipped) {}
}
