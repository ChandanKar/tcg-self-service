package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.service.carbon.CarbonIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Carbon footprint (E37): ingestion of the AWS carbon Data Export. 404 while carbon.enabled is off. */
@RestController
@RequestMapping("/api/v1")
public class CarbonController {

    private final CarbonIngestionService ingestionService;

    public CarbonController(CarbonIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    private static ResponseEntity<Object> off() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found", "message", "Carbon data is not enabled"));
    }

    /** Reads new or changed export files now instead of waiting for the daily run (E37-T01). */
    @PostMapping("/cost-management/carbon/ingest")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> ingest() {
        if (!ingestionService.isEnabled()) return off();
        return ResponseEntity.ok(ingestionService.ingest());
    }
}
