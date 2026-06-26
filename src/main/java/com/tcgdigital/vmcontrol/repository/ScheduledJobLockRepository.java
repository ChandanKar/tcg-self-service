package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScheduledJobLockRepository extends JpaRepository<ScheduledJobLock, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ScheduledJobLock l WHERE l.lockName = :lockName")
    Optional<ScheduledJobLock> findForUpdate(@Param("lockName") String lockName);
}
