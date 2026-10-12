package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ResizeStatus;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmResizeEvent;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Only completed resizes reach the savings ledger; a running safe resize is found (E30-T06). */
class VmResizeEventRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmResizeEventRepository repository;

    private VmResizeEvent event(Vm vm, ResizeStatus status, String to) {
        VmResizeEvent e = new VmResizeEvent(UUID.randomUUID().toString(), vm.getVmId(), "m5.xlarge", to,
                VmResizeEvent.Source.APPLY, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), null);
        e.setStatus(status);
        e.setStage(status.name());
        return repository.save(e);
    }

    @Test
    void theLedgerSeesOnlyCompletedResizesAndARunningOneIsInProgress() {
        Vm vm = newVm(newGroup(newEnvironment("resize-env"), "resize-group"), "resize-vm", VmStatus.RUNNING);
        event(vm, ResizeStatus.COMPLETED, "m5.large");
        event(vm, ResizeStatus.ROLLED_BACK, "m6a.large");
        event(vm, ResizeStatus.FAILED, "m7i.large");

        List<VmResizeEvent> ledger = repository.findByVmIdInAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAsc(
                List.of(vm.getVmId()), Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), Timestamp.from(Instant.now()));

        assertThat(ledger).extracting(VmResizeEvent::getNewInstanceType).containsExactly("m5.large");
        assertThat(repository.existsInProgress(vm.getVmId())).isFalse();

        event(vm, ResizeStatus.STARTING, "m6i.large");
        assertThat(repository.existsInProgress(vm.getVmId())).isTrue();
    }
}
