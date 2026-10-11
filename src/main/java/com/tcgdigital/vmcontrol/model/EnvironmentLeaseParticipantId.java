package com.tcgdigital.vmcontrol.model;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link EnvironmentLeaseParticipant}. */
public class EnvironmentLeaseParticipantId implements Serializable {

    private String leaseId;
    private String userId;

    public EnvironmentLeaseParticipantId() {
    }

    public EnvironmentLeaseParticipantId(String leaseId, String userId) {
        this.leaseId = leaseId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EnvironmentLeaseParticipantId other)) return false;
        return Objects.equals(leaseId, other.leaseId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leaseId, userId);
    }
}
