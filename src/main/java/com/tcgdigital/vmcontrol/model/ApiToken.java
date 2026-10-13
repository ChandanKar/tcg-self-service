package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/** A personal API token (E34-T01): the prefix finds it, only the keyed hash of the whole token is kept. */
@Entity
@Table(name = "api_token")
public class ApiToken {

    @Id
    @Column(name = "token_id", length = 36)
    private String tokenId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "token_prefix", nullable = false, length = 8, columnDefinition = "CHAR(8)")
    private String tokenPrefix;

    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    /** Scope values, comma-separated. */
    @Column(name = "scopes", nullable = false, columnDefinition = "TEXT")
    private String scopes;

    @Column(name = "expires_at", nullable = false)
    private Timestamp expiresAt;

    @Column(name = "last_used_at")
    private Timestamp lastUsedAt;

    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;

    @Column(name = "revoked_at")
    private Timestamp revokedAt;

    @Column(name = "revoked_by", length = 36)
    private String revokedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /** PERSONAL, or SERVICE for a service account's token (E34-T03, V85). */
    @Column(name = "kind", nullable = false, length = 20)
    private String kind = "PERSONAL";

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public List<String> scopeList() {
        return scopes == null || scopes.isBlank() ? List.of() : Arrays.stream(scopes.split(",")).map(String::trim).toList();
    }

    public boolean hasScope(ApiScope scope) {
        return scopeList().contains(scope.value());
    }

    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }
    public Timestamp getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Timestamp lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getLastUsedIp() { return lastUsedIp; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public Timestamp getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Timestamp revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokedBy() { return revokedBy; }
    public void setRevokedBy(String revokedBy) { this.revokedBy = revokedBy; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
