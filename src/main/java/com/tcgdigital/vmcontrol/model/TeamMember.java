package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;

/** A user's membership of a team, from its Entra group or added by an admin (E23-T02). */
@Entity
@Table(name = "team_member")
@IdClass(TeamMember.Key.class)
public class TeamMember {

    public enum Source { ENTRA, MANUAL }

    /** Composite key (team, user). */
    public static class Key implements Serializable {
        private String teamId;
        private String userId;

        public Key() {
        }

        public Key(String teamId, String userId) {
            this.teamId = teamId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(teamId, k.teamId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(teamId, userId);
        }
    }

    @Id
    @Column(name = "team_id", length = 36)
    private String teamId;

    @Id
    @Column(name = "user_id", length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source;

    @Column(name = "synced_at", nullable = false)
    private Timestamp syncedAt;

    public TeamMember() {
    }

    public TeamMember(String teamId, String userId, Source source, Timestamp syncedAt) {
        this.teamId = teamId;
        this.userId = userId;
        this.source = source;
        this.syncedAt = syncedAt;
    }

    public String getTeamId() { return teamId; }
    public String getUserId() { return userId; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Timestamp getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Timestamp syncedAt) { this.syncedAt = syncedAt; }
}
