package com.tcgdigital.vmcontrol.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Estimated monthly cost of waste (E22-T01), from waste.pricing.* properties with us-east-1
 * list prices as defaults. Region differences are out of scope: the UI labels values "estimated".
 */
@Component
public class WastePricing {

    /** USD per GB-month by EBS volume type. */
    static final Map<String, BigDecimal> EBS_DEFAULTS = Map.of(
            "gp2", new BigDecimal("0.10"), "gp3", new BigDecimal("0.08"), "io1", new BigDecimal("0.125"),
            "io2", new BigDecimal("0.125"), "st1", new BigDecimal("0.045"), "sc1", new BigDecimal("0.015"),
            "standard", new BigDecimal("0.05"));

    private final Environment environment;

    public WastePricing(Environment environment) {
        this.environment = environment;
    }

    /** USD per GB-month of an EBS volume type (gp2's rate when the type is unknown). */
    public BigDecimal ebsRate(String volumeType) {
        String type = volumeType == null ? "gp2" : volumeType.toLowerCase(Locale.ROOT);
        BigDecimal fallback = EBS_DEFAULTS.getOrDefault(type, EBS_DEFAULTS.get("gp2"));
        return environment.getProperty("waste.pricing.ebs." + type, BigDecimal.class, fallback);
    }

    public BigDecimal ebsMonthly(String volumeType, int sizeGib) {
        return money(ebsRate(volumeType).multiply(BigDecimal.valueOf(sizeGib)));
    }

    /** Snapshot storage, an upper bound (snapshots are incremental). */
    public BigDecimal snapshotMonthly(int sizeGib) {
        return money(environment.getProperty("waste.pricing.snapshot-gb-month", BigDecimal.class, new BigDecimal("0.05"))
                .multiply(BigDecimal.valueOf(sizeGib)));
    }

    /** A public IPv4 address ($0.005 an hour). */
    public BigDecimal publicIpv4Monthly() {
        return money(environment.getProperty("waste.pricing.public-ipv4-month", BigDecimal.class, new BigDecimal("3.65")));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
