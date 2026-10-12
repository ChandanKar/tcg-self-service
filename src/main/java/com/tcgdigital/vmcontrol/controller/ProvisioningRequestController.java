package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.controller.support.Paging;
import com.tcgdigital.vmcontrol.dto.CreateProvisioningRequestDTO;
import com.tcgdigital.vmcontrol.dto.ProvisioningRequestDTO;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.provisioning.ProvisioningRequestService;
import com.tcgdigital.vmcontrol.service.provisioning.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Requests for an environment from a template, and their approval (E32-T03); 404 while provisioning is off. */
@RestController
@RequestMapping("/api/v1/provisioning-requests")
@Tag(name = "Provisioning requests", description = "Request environments from approved templates")
public class ProvisioningRequestController {

    private final ProvisioningRequestService requestService;
    private final TemplateService templateService;
    private final UserService userService;

    public ProvisioningRequestController(ProvisioningRequestService requestService, TemplateService templateService,
                                         UserService userService) {
        this.requestService = requestService;
        this.templateService = templateService;
        this.userService = userService;
    }

    public record DecisionRequest(String notes) {
    }

    public record ExtendRequest(Integer hours) {
    }

    public record DestroyRequest(Boolean confirm) {
    }

    /** Destroy-time handling (E32-T05); optional so controller slices can leave it out. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.provisioning.ProvisioningDestroyService destroyService;

    @PostMapping("/{requestId}/extend-ttl")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Extend a provisioned environment", description = "Requester or environment admins, within the template's maximum (admins beyond it).")
    public ResponseEntity<ProvisioningRequestDTO> extendTtl(@PathVariable String requestId, @RequestBody ExtendRequest body) {
        if (body == null || body.hours() == null) {
            throw new com.tcgdigital.vmcontrol.exception.ValidationException("hours is required");
        }
        destroyService.extendTtl(requestId, body.hours(), userService.getCurrentUserId());
        return ResponseEntity.ok(requestService.get(requestId, userService.getCurrentUserId()));
    }

    @PostMapping("/{requestId}/destroy")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Destroy a provisioned environment now", description = "Requester or admin, with confirm=true.")
    public ResponseEntity<ProvisioningRequestDTO> destroy(@PathVariable String requestId, @RequestBody(required = false) DestroyRequest body) {
        destroyService.destroyNow(requestId, userService.getCurrentUserId(), body != null && Boolean.TRUE.equals(body.confirm()));
        return ResponseEntity.ok(requestService.get(requestId, userService.getCurrentUserId()));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Request an environment", description = "Checked against the template; waits for approval unless the template needs none.")
    public ResponseEntity<ProvisioningRequestDTO> create(@Valid @RequestBody CreateProvisioningRequestDTO dto) {
        templateService.requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(requestService.create(dto, userService.getCurrentUserId()));
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<ProvisioningRequestDTO>> mine(@RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(requestService.mine(userService.getCurrentUserId(), Paging.of(page, size, 100)));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Requests waiting for you", description = "Requests whose template you may approve, not your own.")
    public ResponseEntity<Page<ProvisioningRequestDTO>> pending(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(requestService.pending(userService.getCurrentUserId(), Paging.of(page, size, 100)));
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProvisioningRequestDTO> get(@PathVariable String requestId) {
        return ResponseEntity.ok(requestService.get(requestId, userService.getCurrentUserId()));
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    public ResponseEntity<ProvisioningRequestDTO> approve(@PathVariable String requestId,
                                                          @RequestBody(required = false) DecisionRequest body) {
        return ResponseEntity.ok(requestService.approve(requestId, userService.getCurrentUserId(), body == null ? null : body.notes()));
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    public ResponseEntity<ProvisioningRequestDTO> reject(@PathVariable String requestId, @RequestBody DecisionRequest body) {
        return ResponseEntity.ok(requestService.reject(requestId, userService.getCurrentUserId(), body == null ? null : body.notes()));
    }

    @PostMapping("/{requestId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProvisioningRequestDTO> cancel(@PathVariable String requestId) {
        return ResponseEntity.ok(requestService.cancel(requestId, userService.getCurrentUserId()));
    }
}
