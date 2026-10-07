package com.tcgdigital.vmcontrol.service.support;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AfterCommitTest extends AbstractIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void runsOnceAfterCommit() {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean ranInsideTransaction = new AtomicBoolean(true);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AfterCommit.run(runs::incrementAndGet);
            ranInsideTransaction.set(runs.get() > 0);
        });

        assertThat(ranInsideTransaction).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void neverRunsOnRollback() {
        AtomicInteger runs = new AtomicInteger();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AfterCommit.run(runs::incrementAndGet);
            status.setRollbackOnly();
        });

        assertThat(runs).hasValue(0);
    }

    @Test
    void runsImmediatelyWithoutTransaction() {
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run(runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void failingTaskDoesNotFailTheCommit() {
        assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                AfterCommit.run(() -> {
                    throw new IllegalStateException("side effect failed");
                })))
                .doesNotThrowAnyException();
    }
}
