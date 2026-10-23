package com.tcgdigital.vmcontrol.service.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** U20-T05: an EC2 instance is an EKS worker node when its tags say which cluster it belongs to. */
class EksNodeTagsTest {

    @Test
    void managedNodeGroupsAwsManagedEksctlAndOwnedNodesAreNodes() {
        assertThat(EksNodeTags.clusterOf(Map.of("eks:cluster-name", "shop", "eks:nodegroup-name", "ng-a"))).isEqualTo("shop");
        assertThat(EksNodeTags.clusterOf(Map.of("aws:eks:cluster-name", "data"))).isEqualTo("data");
        assertThat(EksNodeTags.clusterOf(Map.of("alpha.eksctl.io/cluster-name", "dev-eks"))).isEqualTo("dev-eks");
        // self-managed and Karpenter nodes carry only the ownership tag
        assertThat(EksNodeTags.clusterOf(Map.of("kubernetes.io/cluster/payments", "owned", "karpenter.sh/nodepool", "default")))
                .isEqualTo("payments");
    }

    @Test
    void ordinaryInstancesAreNot() {
        assertThat(EksNodeTags.clusterOf(null)).isNull();
        assertThat(EksNodeTags.clusterOf(Map.of())).isNull();
        assertThat(EksNodeTags.clusterOf(Map.of("Name", "web-01", "tcg:environment", "crm-uat"))).isNull();
        // a load balancer or subnet tag value 'shared' does not make an instance a node
        assertThat(EksNodeTags.clusterOf(Map.of("kubernetes.io/cluster/payments", "shared"))).isNull();
        assertThat(EksNodeTags.clusterOf(Map.of("kubernetes.io/cluster/", "owned"))).isNull();
        Map<String, String> blank = new HashMap<>();
        blank.put("eks:cluster-name", " ");
        assertThat(EksNodeTags.clusterOf(blank)).isNull();
    }
}
