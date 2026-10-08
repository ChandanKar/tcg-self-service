package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findLatestByVmIds returns each VM's latest sample in one query, against MySQL (E10-T04).
 */
class VmMetricSampleLatestQueryTest extends AbstractIntegrationTest {

    @Autowired private VmMetricSampleRepository samples;

    private void sample(Vm vm, Instant at, String cpu) {
        VmMetricSample s = new VmMetricSample();
        s.setMetricSampleId(UUID.randomUUID().toString());
        s.setVm(vm);
        s.setProvider(vm.getProvider());
        s.setProviderVmId(vm.getProviderVmId());
        s.setSampleTime(Timestamp.from(at));
        s.setPeriodSeconds(300);
        s.setCpuUtilization(new BigDecimal(cpu));
        samples.saveAndFlush(s);
    }

    @Test
    void eachVmGetsItsOwnLatestSample() {
        Environment env = newEnvironment("Latest");
        VmGroup group = newGroup(env, "app");
        Vm a = newVm(group, "a", VmStatus.RUNNING);
        Vm b = newVm(group, "b", VmStatus.RUNNING);
        Vm none = newVm(group, "none", VmStatus.STOPPED);
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        sample(a, now.minusSeconds(600), "10");
        sample(a, now.minusSeconds(300), "20");
        sample(b, now.minusSeconds(7200), "90"); // older than any 1-hour window, still the latest

        List<VmMetricSample> latest = samples.findLatestByVmIds(List.of(a.getVmId(), b.getVmId(), none.getVmId()));

        assertThat(latest).extracting(s -> s.getVm().getVmId(), s -> s.getCpuUtilization().intValue())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(a.getVmId(), 20),
                        org.assertj.core.groups.Tuple.tuple(b.getVmId(), 90));
    }
}
