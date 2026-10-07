package com.tcgdigital.vmcontrol.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects (notifications, automation hooks, cloud calls) only once the surrounding
 * transaction has committed.
 *
 * <p>Inside an active transaction the task runs on the committing thread right after commit,
 * and never on rollback; an exception it throws is logged and swallowed so it cannot turn a
 * successful commit into a failure. Outside a transaction it runs immediately and exceptions
 * propagate. Do not put database writes that must be atomic with the transaction in the task;
 * use a {@code REQUIRES_NEW} method for a write that must happen after commit.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.warn("After-commit task failed", e);
                    }
                }
            });
        } else {
            task.run();
        }
    }
}
