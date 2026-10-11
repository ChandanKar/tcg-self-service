package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/** A one-click chat action link (E24-T04): single use, expiring. */
@Entity
@Table(name = "chat_action_token")
public class ChatActionToken {

    @Id
    @Column(name = "jti", length = 36)
    private String jti;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private ChatActionType actionType;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "expires_at", nullable = false)
    private Timestamp expiresAt;

    @Column(name = "used_at")
    private Timestamp usedAt;

    @Column(name = "used_by_user_id", length = 36)
    private String usedByUserId;

    public ChatActionToken() {
    }

    public ChatActionToken(String jti, ChatActionType actionType, String environmentId, String targetId,
                           Timestamp createdAt, Timestamp expiresAt) {
        this.jti = jti;
        this.actionType = actionType;
        this.environmentId = environmentId;
        this.targetId = targetId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getJti() { return jti; }
    public ChatActionType getActionType() { return actionType; }
    public String getEnvironmentId() { return environmentId; }
    public String getTargetId() { return targetId; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getExpiresAt() { return expiresAt; }
    public Timestamp getUsedAt() { return usedAt; }
    public String getUsedByUserId() { return usedByUserId; }
}
