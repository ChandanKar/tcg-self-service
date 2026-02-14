package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;

/**
 * DTO for state sync status response.
 */
public class StateSyncStatusDTO {

    private Timestamp lastSyncTime;
    private String lastSyncStatus;
    private int totalVmsSynced;
    private int driftDetected;
    private int syncErrors;
    private long nextSyncInSeconds;
    private boolean syncInProgress;

    public StateSyncStatusDTO() {
    }

    // Getters and Setters
    public Timestamp getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(Timestamp lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public String getLastSyncStatus() {
        return lastSyncStatus;
    }

    public void setLastSyncStatus(String lastSyncStatus) {
        this.lastSyncStatus = lastSyncStatus;
    }

    public int getTotalVmsSynced() {
        return totalVmsSynced;
    }

    public void setTotalVmsSynced(int totalVmsSynced) {
        this.totalVmsSynced = totalVmsSynced;
    }

    public int getDriftDetected() {
        return driftDetected;
    }

    public void setDriftDetected(int driftDetected) {
        this.driftDetected = driftDetected;
    }

    public int getSyncErrors() {
        return syncErrors;
    }

    public void setSyncErrors(int syncErrors) {
        this.syncErrors = syncErrors;
    }

    public long getNextSyncInSeconds() {
        return nextSyncInSeconds;
    }

    public void setNextSyncInSeconds(long nextSyncInSeconds) {
        this.nextSyncInSeconds = nextSyncInSeconds;
    }

    public boolean isSyncInProgress() {
        return syncInProgress;
    }

    public void setSyncInProgress(boolean syncInProgress) {
        this.syncInProgress = syncInProgress;
    }
}

