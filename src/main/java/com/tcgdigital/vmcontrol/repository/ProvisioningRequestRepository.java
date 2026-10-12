package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

public interface ProvisioningRequestRepository extends JpaRepository<ProvisioningRequest, String> {

    List<ProvisioningRequest> findByStatusOrderByCreatedAtAsc(ProvisioningStatus status);

    List<ProvisioningRequest> findByStatusInOrderByCreatedAtAsc(Collection<ProvisioningStatus> statuses);

    List<ProvisioningRequest> findByRequestedByUserIdOrderByCreatedAtDesc(String userId);

    List<ProvisioningRequest> findByDestroyAtBeforeAndStatus(Timestamp before, ProvisioningStatus status);

    boolean existsByTemplateIdAndStatusIn(String templateId, Collection<ProvisioningStatus> statuses);

}
