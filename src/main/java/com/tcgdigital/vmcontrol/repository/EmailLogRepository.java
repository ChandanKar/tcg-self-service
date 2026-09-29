package com.tcgdigital.vmcontrol.repository;

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
}
