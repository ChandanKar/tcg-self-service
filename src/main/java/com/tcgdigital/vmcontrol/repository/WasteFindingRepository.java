package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.WasteFinding;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface WasteFindingRepository extends JpaRepository<WasteFinding, String> {

    Optional<WasteFinding> findByFindingTypeAndRegionAndResourceId(WasteFindingType type, String region, String resourceId);

    /** Findings matching the given filters (each optional), for the Waste page (E22-T03). */
    @Query("SELECT f FROM WasteFinding f WHERE (:type IS NULL OR f.findingType = :type) "
            + "AND (:region IS NULL OR f.region = :region) AND (:status IS NULL OR f.status = :status) "
            + "AND (:environmentId IS NULL OR f.environmentId = :environmentId)")
    org.springframework.data.domain.Page<WasteFinding> search(@Param("type") WasteFindingType type, @Param("region") String region,
                                                              @Param("status") WasteFindingStatus status,
                                                              @Param("environmentId") String environmentId,
                                                              org.springframework.data.domain.Pageable pageable);

    /** Per type: [type, count, monthly total] of the given status. */
    @Query("SELECT f.findingType, COUNT(f), SUM(f.estMonthlyCost) FROM WasteFinding f WHERE f.status = :status GROUP BY f.findingType")
    List<Object[]> totalsByType(@Param("status") WasteFindingStatus status);

    /** OPEN findings of a type in a region that the run starting at {@code since} did not see. */
    @Query("SELECT f FROM WasteFinding f WHERE f.findingType = :type AND f.region = :region "
            + "AND f.status = :status AND f.lastSeenAt < :since")
    List<WasteFinding> findNotSeenSince(@Param("type") WasteFindingType type, @Param("region") String region,
                                        @Param("status") WasteFindingStatus status, @Param("since") Timestamp since);
}
