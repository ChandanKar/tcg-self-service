package com.tcgdigital.vmcontrol.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Names for VMs whose instance has no Name tag (U01-T06). They used to be named after the instance id,
 * which then reached everyone through the VM's name. The placeholder is "unnamed-" and 6 hex characters of
 * a SHA-256 of the id: stable for the same instance, and the id cannot be read back from it. Migration V90
 * renames existing VMs the same way (MySQL SHA2(provider_vm_id, 256)).
 */
public final class UnnamedVmNames {

    public static final String PREFIX = "unnamed-";

    private UnnamedVmNames() {
    }

    public static String placeholderFor(String providerVmId) {
        return PREFIX + hash(providerVmId).substring(0, 6);
    }

    /** A short, stable suffix that tells two same-named VMs apart without quoting the instance id. */
    public static String suffixFor(String providerVmId) {
        return hash(providerVmId).substring(0, 4);
    }

    /** True while the VM still has no real name: the placeholder, or the instance id itself (older rows). */
    public static boolean isPlaceholder(String name, String providerVmId) {
        if (name == null || providerVmId == null) {
            return false;
        }
        return name.equalsIgnoreCase(providerVmId) || name.equalsIgnoreCase(placeholderFor(providerVmId));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
