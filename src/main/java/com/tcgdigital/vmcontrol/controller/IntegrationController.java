package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.ExternalTicket;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.jira.JiraTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Outside integrations (E24-T06): Jira tickets for recommendations and anomalies. Creating one
 * needs ADMIN, or administering the source's environment.
 */
@RestController
@RequestMapping("/api/v1/integrations")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Integrations", description = "Jira tickets from recommendations and anomalies")
public class IntegrationController {

    private final JiraTicketService ticketService;
    private final SecurityService securityService;
    private final UserService userService;

    public IntegrationController(JiraTicketService ticketService, SecurityService securityService, UserService userService) {
        this.ticketService = ticketService;
        this.securityService = securityService;
        this.userService = userService;
    }

    public record TicketRequest(String sourceType, String sourceId) {}

    @GetMapping("/jira/status")
    @Operation(summary = "Whether Jira tickets can be created (for showing the buttons)")
    public JiraTicketService.Status status() {
        return ticketService.status();
    }

    @GetMapping("/jira/tickets")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Existing tickets for sources of one type (sourceIds comma-separated), by source id")
    public Map<String, JiraTicketService.TicketResult> existing(@RequestParam String sourceType,
                                                                 @RequestParam(defaultValue = "") String sourceIds) {
        List<String> ids = Arrays.stream(sourceIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).limit(500).toList();
        return ticketService.existing(JiraTicketService.sourceType(sourceType), ids);
    }

    @PostMapping("/jira/tickets")
    @Operation(summary = "Create (or return the existing) Jira issue for a recommendation or anomaly")
    public ResponseEntity<JiraTicketService.TicketResult> create(@RequestBody TicketRequest request) {
        ExternalTicket.SourceType type = JiraTicketService.sourceType(request == null ? null : request.sourceType());
        String sourceId = request.sourceId() == null ? "" : request.sourceId().trim();
        if (sourceId.isEmpty()) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException("sourceId is required");
        }
        if (!securityService.isAdmin()) {
            String environmentId = ticketService.environmentOf(type, sourceId);
            if (environmentId == null) {
                throw new UnauthorizedException("Only admins create tickets for sources without an environment");
            }
            securityService.assertCanAdminister(environmentId);
        }
        JiraTicketService.TicketResult result = ticketService.create(type, sourceId, userService.getCurrentUserId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}
