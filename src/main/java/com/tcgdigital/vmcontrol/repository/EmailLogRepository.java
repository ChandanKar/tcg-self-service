package com.tcgdigital.vmcontrol.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.tcgdigital.vmcontrol.model.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link EmailLog} — the "who have we emailed" admin view.
 */
@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, String> {

    Page<EmailLog> findAllByOrderBySentAtDesc(Pageable pageable);

    Page<EmailLog> findByEnvironmentIdOrderBySentAtDesc(String environmentId, Pageable pageable);

    /** Retention (E11-T07): delete up to {@code limit} of the oldest rows before {@code cutoff}. */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM email_log WHERE sent_at < :cutoff ORDER BY sent_at LIMIT :limit")
    int deleteOlderThan(@Param("cutoff") java.sql.Timestamp cutoff, @Param("limit") int limit);
}
