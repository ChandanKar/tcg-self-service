package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** U01-T01: databases are labelled from rdsKind, so the UI never reads the identifier. */
class VmDtoRdsKindTest {

    @Test
    void aDbInstanceAndAnAuroraClusterAreToldApart() {
        assertThat(VmDTO.rdsKindOf(CloudProvider.AWS_RDS, "db:orders")).isEqualTo("INSTANCE");
        assertThat(VmDTO.rdsKindOf(CloudProvider.AWS_RDS, "cluster:orders")).isEqualTo("CLUSTER");
    }

    @Test
    void otherProvidersHaveNoRdsKind() {
        assertThat(VmDTO.rdsKindOf(CloudProvider.AWS, "i-0123456789abcdef0")).isNull();
        assertThat(VmDTO.rdsKindOf(CloudProvider.AWS_EKS, "cluster/nodes")).isNull();
        assertThat(VmDTO.rdsKindOf(CloudProvider.AWS_RDS, null)).isNull();
    }
}
