package com.tcgdigital.vmcontrol.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs side effects (notifications, automation hooks, cloud calls) only once the surrounding
 * transaction has committed.
 *
 * <p>Inside an active transaction the task runs on the committing thread right after commit,
 * and never on rollback; an exception it throws is logged and swallowed so it cannot turn a
 * successful commit into a failure. Outside a transaction it runs immediately and exceptions
 * propagate. Do not put database writes that must be atomic with the transaction in the task.
 *
 * <p>After commit the task runs in a new transaction of its own: in {@code afterCommit} the
 * committed transaction's resources are still bound, so a plain repository call would join it
 * and never be flushed (a notification row would be silently lost).
 */
@Component
public class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private final TransactionTemplate newTransaction;

    public AfterCommit(PlatformTransactionManager transactionManager) {
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void run(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        newTransaction.executeWithoutResult(status -> task.run());
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
