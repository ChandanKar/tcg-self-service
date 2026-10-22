package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.exception.RegionUnknownException;

import java.util.regex.Pattern;

/**
 * Region rules shared by the AWS clients (U19-T04). A regional call goes to the region stored on
 * its VM, node group or environment; a blank one is refused instead of being sent to aws.region,
 * where it would only fail as "not found" (or, worse, act on a namesake).
 */
public final class AwsRegions {

    /** An AWS region code: eu-west-1, ap-southeast-5, me-central-1. */
    public static final Pattern REGION_CODE = Pattern.compile("^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$");

    private AwsRegions() {
    }

    /** The region, or a RegionUnknownException naming what the call was for. */
    public static String require(String region, Object what) {
        if (region == null || region.isBlank()) {
            throw new RegionUnknownException("No AWS region is known for " + what
                    + ". Set the environment's region in its settings (Details).");
        }
        return region.trim();
    }

    public static boolean isRegionCode(String value) {
        return value != null && REGION_CODE.matcher(value.trim()).matches();
    }
}
