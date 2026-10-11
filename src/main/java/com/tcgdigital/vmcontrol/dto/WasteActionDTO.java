package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.WasteAction;
import com.tcgdigital.vmcontrol.model.WasteFindingType;

import java.sql.Timestamp;

/** A waste fix action and its finding (E22-T04). */
public record WasteActionDTO(
        String actionId,
        String findingId,
        WasteFindingType findingType,
        String region,
        String resourceId,
        WasteAction.Type actionType,
        WasteAction.Status status,
        String proposedBy,
        Timestamp proposedAt,
        String approvedBy,
        Timestamp approvedAt,
        Timestamp executedAt,
        Timestamp rolledBackAt,
        String errorMessage,
        /** Whether an EXECUTED action of this type can be rolled back. */
        boolean rollbackSupported,
        /** The finding's details JSON (e.g. the public IP to type before releasing it). */
        String findingDetails) {
}
