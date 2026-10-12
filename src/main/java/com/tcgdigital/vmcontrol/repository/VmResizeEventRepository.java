package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmResizeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface VmResizeEventRepository extends JpaRepository<VmResizeEvent, String> {

    Optional<VmResizeEvent> findTopByVmIdAndChangedAtLessThanOrderByChangedAtDesc(String vmId, Timestamp before);

    /**
     * Completed resizes of these VMs in [since, before), oldest first (the ledger keeps each VM's
     * latest). A safe resize still running, rolled back or failed changed nothing (E30-T06).
     */
    @Query("SELECT e FROM VmResizeEvent e WHERE e.vmId IN :vmIds AND e.changedAt >= :since AND e.changedAt < :before "
            + "AND e.status = com.tcgdigital.vmcontrol.model.ResizeStatus.COMPLETED ORDER BY e.changedAt ASC")
    List<VmResizeEvent> findByVmIdInAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAsc(
            @Param("vmIds") List<String> vmIds, @Param("since") Timestamp since, @Param("before") Timestamp before);

    /** A safe resize of this VM still in progress (E30-T06). */
    @Query("SELECT COUNT(e) > 0 FROM VmResizeEvent e WHERE e.vmId = :vmId AND e.status IN "
            + "(com.tcgdigital.vmcontrol.model.ResizeStatus.REQUESTED, com.tcgdigital.vmcontrol.model.ResizeStatus.STOPPING, "
            + "com.tcgdigital.vmcontrol.model.ResizeStatus.RESIZING, com.tcgdigital.vmcontrol.model.ResizeStatus.STARTING, "
            + "com.tcgdigital.vmcontrol.model.ResizeStatus.ROLLING_BACK)")
    boolean existsInProgress(@Param("vmId") String vmId);
}
