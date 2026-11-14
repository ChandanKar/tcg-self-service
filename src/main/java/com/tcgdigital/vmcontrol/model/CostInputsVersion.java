package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A counter that moves whenever an input of the fleet's cost figures is written through JPA (U26-T01): a
 * VM state change (vm_state_history) or a daily cost snapshot. The briefly reused fleet cost days in
 * EnvironmentCostService are rebuilt as soon as it moves, so a start or stop shows at once instead of
 * after the cache's few seconds.
 */
public class CostInputsVersion {

    private static final AtomicLong VERSION = new AtomicLong();

    public static long current() {
        return VERSION.get();
    }

    public static void bump() {
        VERSION.incrementAndGet();
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object entity) {
        bump();
    }
}
