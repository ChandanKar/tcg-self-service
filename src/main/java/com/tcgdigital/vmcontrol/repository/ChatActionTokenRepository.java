package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ChatActionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;

/** One-click chat action links (E24-T04). */
public interface ChatActionTokenRepository extends JpaRepository<ChatActionToken, String> {

    /** Marks the link used; 0 when someone already used it (the single-use guarantee). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ChatActionToken t SET t.usedAt = :now, t.usedByUserId = :userId WHERE t.jti = :jti AND t.usedAt IS NULL")
    int markUsed(@Param("jti") String jti, @Param("userId") String userId, @Param("now") Timestamp now);
}
