package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AwsAccount;

import java.sql.Timestamp;
import java.util.List;

/**
 * An AWS account as the admin API shows it (E28-T07): the external ID is masked to its last 4
 * characters. lastErrorSummary says the last check's error in plain words (U14-T03); lastError keeps
 * AWS's message. enabledRegions are the regions the account has enabled in AWS as last read (U19-T01),
 * empty until read; refusedRegions refused the app's calls in the last day and are skipped.
 */
public record AwsAccountDTO(
        String awsAccountRef,
        String accountNumber,
        String displayName,
        String roleArn,
        String externalIdMasked,
        List<String> regions,
        boolean isDefault,
        boolean isBillingAccount,
        boolean isActive,
        String source,
        long environmentCount,
        Timestamp lastVerifiedAt,
        String lastError,
        String lastErrorSummary,
        List<String> enabledRegions,
        List<String> refusedRegions,
        java.time.Instant regionsReadAt
) {
    public static AwsAccountDTO from(AwsAccount a, long environmentCount) {
        return from(a, environmentCount, List.of());
    }

    public static AwsAccountDTO from(AwsAccount a, long environmentCount,
                                     List<com.tcgdigital.vmcontrol.service.aws.AwsRegionService.RegionView> regions) {
        List<String> enabled = regions.stream().filter(r -> r.optInStatus() != null)
                .map(com.tcgdigital.vmcontrol.service.aws.AwsRegionService.RegionView::region).toList();
        List<String> refused = regions.stream().filter(com.tcgdigital.vmcontrol.service.aws.AwsRegionService.RegionView::refused)
                .map(com.tcgdigital.vmcontrol.service.aws.AwsRegionService.RegionView::region).toList();
        java.time.Instant readAt = regions.stream().filter(r -> r.optInStatus() != null && r.readAt() != null)
                .map(com.tcgdigital.vmcontrol.service.aws.AwsRegionService.RegionView::readAt)
                .min(java.time.Instant::compareTo).orElse(null);
        return new AwsAccountDTO(a.getAwsAccountRef(), a.getAccountNumber(), a.getDisplayName(), a.getRoleArn(),
                mask(a.getExternalId()), a.getRegionList(), a.isDefault(), a.isBillingAccount(), a.isActive(),
                a.getSource().name(), environmentCount, a.getLastVerifiedAt(), a.getLastError(),
                com.tcgdigital.vmcontrol.service.aws.AwsAccountErrors.summary(a.getLastError()), enabled, refused, readAt);
    }

    static String mask(String externalId) {
        if (externalId == null || externalId.isEmpty()) {
            return null;
        }
        return externalId.length() <= 4 ? "****" : "****" + externalId.substring(externalId.length() - 4);
    }
}
