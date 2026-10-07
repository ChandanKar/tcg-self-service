package com.tcgdigital.vmcontrol.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Content-Security-Policy violation reports from browsers and logs them, so a
 * report-only rollout shows in the server logs which pages would break once enforced.
 * Anonymous by design (browsers send reports without credentials); the body is untrusted,
 * so it is truncated and logged as data only.
 */
@RestController
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);
    static final int MAX_LOGGED_CHARS = 2000;

    @PostMapping(SecurityHeaders.REPORT_PATH)
    public ResponseEntity<Void> report(@RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) {
            String report = body.length() > MAX_LOGGED_CHARS ? body.substring(0, MAX_LOGGED_CHARS) + "…" : body;
            log.warn("CSP violation report: {}", report.replaceAll("[\\r\\n]+", " "));
        }
        return ResponseEntity.noContent().build();
    }
}
