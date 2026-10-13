package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, String> {

    Optional<ApiToken> findByTokenPrefix(String tokenPrefix);

    /** Tokens that still work, for the admin list (E34-T05). */
    @Query("SELECT t FROM ApiToken t WHERE t.revokedAt IS NULL AND t.expiresAt > :now")
    org.springframework.data.domain.Page<ApiToken> findActive(@Param("now") Timestamp now, org.springframework.data.domain.Pageable pageable);

    /** An admin revoking any token (E34-T05); 0 when already revoked. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE ApiToken t SET t.revokedAt = :at, t.revokedBy = :by WHERE t.tokenId = :id AND t.revokedAt IS NULL")
    int revokeAny(@Param("id") String tokenId, @Param("by") String revokedBy, @Param("at") Timestamp at);

    boolean existsByTokenPrefix(String tokenPrefix);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Tokens that still work: not revoked and not expired. */
    @Query("SELECT COUNT(t) FROM ApiToken t WHERE t.userId = :userId AND t.revokedAt IS NULL AND t.expiresAt > :now")
    long countActive(@Param("userId") String userId, @Param("now") Timestamp now);

    /** Revokes a user's token; 0 when it is not theirs or already revoked. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE ApiToken t SET t.revokedAt = :at, t.revokedBy = :by WHERE t.tokenId = :id AND t.userId = :userId AND t.revokedAt IS NULL")
    int revoke(@Param("id") String tokenId, @Param("userId") String userId, @Param("by") String revokedBy, @Param("at") Timestamp at);

    /** Revokes every token of a user that still works (E34-T03: a deactivated service account). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE ApiToken t SET t.revokedAt = :at, t.revokedBy = :by WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllOf(@Param("userId") String userId, @Param("by") String revokedBy, @Param("at") Timestamp at);

    /** Records a use (E34-T02), at most once a minute per token. */
    @Modifying
    @Transactional
    @Query("UPDATE ApiToken t SET t.lastUsedAt = :at, t.lastUsedIp = :ip WHERE t.tokenId = :id")
    int touch(@Param("id") String tokenId, @Param("at") Timestamp at, @Param("ip") String ip);
}
