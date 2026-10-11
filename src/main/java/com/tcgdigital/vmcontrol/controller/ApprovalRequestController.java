package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.ApprovalRequestDTO;
import com.tcgdigital.vmcontrol.service.ApprovalRequestService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Approval requests (E25-T04): requesters see and cancel theirs; approvers see what waits for
 * them and approve or deny. Only snapshotted approvers decide, never the requester.
 */
@RestController
@RequestMapping("/api/v1/approval-requests")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Approval requests", description = "Starts and extensions waiting for approval")
public class ApprovalRequestController {

    private final ApprovalRequestService requestService;
    private final UserService userService;

    public ApprovalRequestController(ApprovalRequestService requestService, UserService userService) {
        this.requestService = requestService;
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "view=mine (default) or to-review")
    public List<ApprovalRequestDTO> list(@RequestParam(defaultValue = "mine") String view) {
        return requestService.list(userService.getCurrentUserId(), view);
    }

    @GetMapping("/{requestId}")
    public ApprovalRequestDTO get(@PathVariable String requestId) {
        return requestService.get(requestId, userService.getCurrentUserId());
    }

    @PostMapping("/{requestId}/approve")
    @Operation(summary = "Approve; the operation runs as the requester once the approvers agree (ANY or ALL)")
    public ApprovalRequestDTO approve(@PathVariable String requestId,
                                      @RequestBody(required = false) ApprovalRequestDTO.DecisionRequest body) {
        return requestService.approve(requestId, userService.getCurrentUserId(), body == null ? null : body.reason());
    }

    @PostMapping("/{requestId}/deny")
    @Operation(summary = "Deny with a reason; the requester is told")
    public ApprovalRequestDTO deny(@PathVariable String requestId, @RequestBody(required = false) ApprovalRequestDTO.DecisionRequest body) {
        return requestService.deny(requestId, userService.getCurrentUserId(), body == null ? null : body.reason());
    }

    @PostMapping("/{requestId}/cancel")
    @Operation(summary = "The requester withdraws a pending request")
    public ApprovalRequestDTO cancel(@PathVariable String requestId) {
        return requestService.cancel(requestId, userService.getCurrentUserId());
    }
}
