package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.dto.json.AdminOnly;

import java.time.Instant;
import java.util.List;

/**
 * "Found in AWS — needs review" (U19-T03): EC2 instances and EKS clusters found in any account and
 * enabled region that no environment holds and nobody chose to ignore.
 *
 * @param items       what was found, newest scan
 * @param scannedAt   when the regions were swept (the list is kept a few minutes)
 * @param unreachable account/region targets that could not be read ("eu-west-1 (account Payments)")
 * @param ignored     how many resources are ignored
 */
public record FoundInAwsDTO(List<Item> items, Instant scannedAt, List<String> unreachable, long ignored) {

    /**
     * One resource.
     *
     * @param kind              EC2 or EKS
     * @param ref               EC2: an opaque reference for Ignore and Add (U01-T02); EKS: null (the cluster name is used)
     * @param resourceId        administrators only: the instance id; EKS: the cluster name
     * @param name              the Name tag or the cluster name; null when an instance has none
     * @param zone              EC2: the availability zone
     * @param taggedEnvironment the environment its tcg:environment tag names, when one exists (it joins it at the next discovery)
     */
    public record Item(String kind, String ref, @AdminOnly String resourceId, String name, String accountRef, String accountName,
                       String region, String zone, String size, String state, String taggedEnvironment, String taggedEnvironmentId) { }

    /** An ignored resource, to bring back. */
    public record Ignored(String id, String kind, @AdminOnly String resourceId, String name, String accountName, String region,
                          Instant ignoredAt) { }
}
