package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.WasteAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WasteActionRepository extends JpaRepository<WasteAction, String> {

    List<WasteAction> findByStatusOrderByProposedAtDesc(WasteAction.Status status);

    List<WasteAction> findAllByOrderByProposedAtDesc();

    List<WasteAction> findByFindingIdOrderByProposedAtDesc(String findingId);
}
