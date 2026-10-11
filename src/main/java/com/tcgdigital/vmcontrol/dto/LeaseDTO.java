package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;
import java.util.List;

/**
 * An environment's lease as the UI needs it (E15). {@code pendingExtension} is filled once
 * extension requests exist (E15-T04); {@code permissions} drive which buttons the UI shows.
 */
public class LeaseDTO {

    /** A user keeping the environment running. */
    public record Participant(String userId, String displayName, Timestamp joinedAt) {
    }

    /** What the caller may do with this lease. */
    public record Permissions(boolean canExtend, boolean canManagePolicy) {
    }

    private String leaseId;
    private String environmentId;
    private String status;
    private String source;
    private String startedByUserId;
    private String startedByName;
    private Timestamp startedAt;
    private Timestamp endsAt;
    private int extendedMinutesTotal;
    private List<Participant> participants = List.of();
    private Object pendingExtension;
    private Permissions permissions;

    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStartedByUserId() { return startedByUserId; }
    public void setStartedByUserId(String startedByUserId) { this.startedByUserId = startedByUserId; }
    public String getStartedByName() { return startedByName; }
    public void setStartedByName(String startedByName) { this.startedByName = startedByName; }
    public Timestamp getStartedAt() { return startedAt; }
    public void setStartedAt(Timestamp startedAt) { this.startedAt = startedAt; }
    public Timestamp getEndsAt() { return endsAt; }
    public void setEndsAt(Timestamp endsAt) { this.endsAt = endsAt; }
    public int getExtendedMinutesTotal() { return extendedMinutesTotal; }
    public void setExtendedMinutesTotal(int extendedMinutesTotal) { this.extendedMinutesTotal = extendedMinutesTotal; }
    public List<Participant> getParticipants() { return participants; }
    public void setParticipants(List<Participant> participants) { this.participants = participants; }
    public Object getPendingExtension() { return pendingExtension; }
    public void setPendingExtension(Object pendingExtension) { this.pendingExtension = pendingExtension; }
    public Permissions getPermissions() { return permissions; }
    public void setPermissions(Permissions permissions) { this.permissions = permissions; }
}
