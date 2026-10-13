package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CarbonIngestFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarbonIngestFileRepository extends JpaRepository<CarbonIngestFile, String> {
}
