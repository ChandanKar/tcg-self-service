package com.tcgdigital.vmcontrol.service.support;

import java.util.Map;

/**
 * Whether an EC2 instance is a worker node of an EKS cluster, from its tags (U20-T05). A node belongs
 * to its node group (or Karpenter, or eksctl): it is started, stopped and replaced through the cluster,
 * so it is never offered or added as a VM of its own.
 *
 * <p>EKS managed node groups tag their instances {@code eks:cluster-name}; AWS-managed ones also
 * {@code aws:eks:cluster-name}; eksctl {@code alpha.eksctl.io/cluster-name}; and every node joined to a
 * cluster, self-managed and Karpenter ones included, {@code kubernetes.io/cluster/<name> = owned}.
 */
public final class EksNodeTags {

    private static final String[] CLUSTER_KEYS = { "eks:cluster-name", "aws:eks:cluster-name", "alpha.eksctl.io/cluster-name" };
    private static final String OWNED_PREFIX = "kubernetes.io/cluster/";

    private EksNodeTags() {
    }

    /** The cluster the instance is a node of, or null when it is not a node. */
    public static String clusterOf(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        for (String key : CLUSTER_KEYS) {
            String value = tags.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            String key = tag.getKey();
            if (key != null && key.startsWith(OWNED_PREFIX) && key.length() > OWNED_PREFIX.length()
                    && "owned".equalsIgnoreCase(String.valueOf(tag.getValue()).trim())) {
                return key.substring(OWNED_PREFIX.length());
            }
        }
        return null;
    }
}
