package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmStatus;

/**
 * How many VMs of one group are in each state (U05-T03), for the grouped power strip ('RRR|RR|SS')
 * and lines like "1 failed · 2 stopped". The buckets match the strip's lights:
 * running = RUNNING; busy = STARTING or STOPPING; stopped = STOPPED;
 * problem = ERROR, NOT_FOUND or TERMINATED; unknown = UNKNOWN. A registered VM stays UNKNOWN until
 * the cloud has been read (every status change also stamps lastStateSyncAt), so UNKNOWN is also
 * "not read from AWS yet".
 */
public record GroupStateCountsDTO(String groupId, String name, Integer sequencePosition,
                                  long total, long running, long busy, long stopped, long problem, long unknown) {

    public static GroupStateCountsDTO empty(String groupId, String name, Integer sequencePosition) {
        return new GroupStateCountsDTO(groupId, name, sequencePosition, 0, 0, 0, 0, 0, 0);
    }

    /** This group's counts plus {@code count} VMs in {@code status}. */
    public GroupStateCountsDTO plus(VmStatus status, long count) {
        long r = running, b = busy, s = stopped, p = problem, u = unknown;
        switch (status == null ? VmStatus.UNKNOWN : status) {
            case RUNNING -> r += count;
            case STARTING, STOPPING -> b += count;
            case STOPPED -> s += count;
            case ERROR, NOT_FOUND, TERMINATED -> p += count;
            default -> u += count;
        }
        return new GroupStateCountsDTO(groupId, name, sequencePosition, total + count, r, b, s, p, u);
    }
}
