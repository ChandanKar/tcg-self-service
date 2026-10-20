package com.tcgdigital.vmcontrol.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What an EKS node group's Vm.metadata says about its nodes (written by EksSyncService and by
 * EksCloudProviderService before a scale-to-0): the instance type, the desired size and the disk
 * size. While the group runs, desiredSize is its live size; once scaled to 0 it is the size saved
 * before stopping, which a start restores. Missing or unreadable metadata reads as nothing known.
 */
public record EksNodeGroupInfo(String instanceType, int desiredSize, int diskSizeGib) {

    public static final EksNodeGroupInfo UNKNOWN = new EksNodeGroupInfo(null, 0, 0);

    private static final ObjectMapper JSON = new ObjectMapper();

    public static EksNodeGroupInfo parse(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return UNKNOWN;
        }
        try {
            JsonNode node = JSON.readTree(metadata);
            String instanceType = node.hasNonNull("instanceType") ? node.get("instanceType").asText() : null;
            int desiredSize = node.hasNonNull("desiredSize") ? Math.max(node.get("desiredSize").asInt(), 0) : 0;
            int diskSizeGib = node.hasNonNull("diskSizeGib") ? Math.max(node.get("diskSizeGib").asInt(), 0) : 0;
            return new EksNodeGroupInfo(instanceType, desiredSize, diskSizeGib);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
