package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.EksNodeGroupInfo;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** U07-T08: an EKS node group's nodes come from its metadata; other VMs carry none. */
class VmDtoEksNodeGroupTest {

    private static Vm vm(CloudProvider provider, VmStatus status, String metadata) {
        VmGroup group = new VmGroup();
        group.setGroupId("g1");
        group.setName("system-nodes");
        Vm vm = new Vm();
        vm.setVmId("v1");
        vm.setGroup(group);
        vm.setName("system-nodes");
        vm.setProvider(provider);
        vm.setStatus(status);
        vm.setMetadata(metadata);
        return vm;
    }

    @Test
    void aRunningNodeGroupRunsItsDesiredSize() {
        VmDTO dto = VmDTO.fromEntity(vm(CloudProvider.AWS_EKS, VmStatus.RUNNING,
                "{\"minSize\":1,\"desiredSize\":3,\"instanceType\":\"m6i.large\"}"));
        assertThat(dto.getEksNodeGroup()).isEqualTo(new VmDTO.EksNodeGroupDTO("m6i.large", 3, 3, "ON_DEMAND"));
    }

    @Test
    void aScaledDownGroupRunsNoNodesButKeepsTheSizeItStartsWith() {
        Vm stopped = vm(CloudProvider.AWS_EKS, VmStatus.STOPPED, "{\"minSize\":1,\"desiredSize\":2,\"instanceType\":\"c6i.xlarge\"}");
        stopped.setPurchaseOption(PurchaseOption.SPOT);
        assertThat(VmDTO.fromEntity(stopped).getEksNodeGroup())
                .isEqualTo(new VmDTO.EksNodeGroupDTO("c6i.xlarge", 2, 0, "SPOT"));
    }

    @Test
    void otherVmsHaveNoNodeGroup() {
        assertThat(VmDTO.fromEntity(vm(CloudProvider.AWS, VmStatus.RUNNING, null)).getEksNodeGroup()).isNull();
    }

    @Test
    void missingOrBrokenMetadataReadsAsNothingKnown() {
        assertThat(EksNodeGroupInfo.parse(null)).isEqualTo(EksNodeGroupInfo.UNKNOWN);
        assertThat(EksNodeGroupInfo.parse("not json")).isEqualTo(EksNodeGroupInfo.UNKNOWN);
        assertThat(EksNodeGroupInfo.parse("{\"desiredSize\":-1}").desiredSize()).isZero();
    }
}
