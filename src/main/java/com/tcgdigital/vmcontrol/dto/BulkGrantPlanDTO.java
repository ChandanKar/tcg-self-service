package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;
import java.util.List;

/**
 * What a bulk grant does, one row per (person, environment or group) (U10-T02): NEW (no access
 * there yet), EXTEND (same level, later end), UPGRADE (a higher level) or SKIP with the reason
 * (already more, already as long, or the reviewer's own access). The preview and the applied
 * result have this shape; counts are distinct people and environments, rows that change, rows
 * skipped.
 */
public record BulkGrantPlanDTO(int people, int environments, int changes, int skipped, List<Row> rows) {

    public enum Outcome { NEW, EXTEND, UPGRADE, SKIP }

    public record Row(String userId, String userName, String userEmail, String environmentId, String environmentName,
                      String scopeType, String scopeId, String scopeName, Outcome outcome, String currentLevel,
                      Timestamp currentExpiresAt, String resultLevel, Timestamp resultExpiresAt, String reason) {
    }
}
