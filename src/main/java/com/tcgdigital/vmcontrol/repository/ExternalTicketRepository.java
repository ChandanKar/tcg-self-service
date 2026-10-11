package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ExternalTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Outside-tracker tickets (E24-T06). */
public interface ExternalTicketRepository extends JpaRepository<ExternalTicket, String> {

    Optional<ExternalTicket> findBySourceTypeAndSourceIdAndSystem(ExternalTicket.SourceType sourceType, String sourceId, String system);

    List<ExternalTicket> findBySourceTypeAndSystemAndSourceIdIn(ExternalTicket.SourceType sourceType, String system,
                                                                Collection<String> sourceIds);
}
