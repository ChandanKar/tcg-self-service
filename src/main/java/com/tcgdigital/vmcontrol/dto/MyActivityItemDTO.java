package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;

/**
 * One entry of {@code GET /api/v1/users/me/activity}: something the signed-in user did, or
 * something that happened to their access.
 *
 * @param kind        {@code OPERATION} or {@code ACCESS}
 * @param event       for OPERATION the operation type ({@code START}, {@code STOP},
 *                    {@code RESTART}); for ACCESS one of {@code REQUESTED}, {@code APPROVED},
 *                    {@code DENIED}, {@code CANCELLED}, {@code GRANTED}, {@code REVOKED},
 *                    {@code EXPIRED}
 * @param status      OPERATION only: the execution status ({@code COMPLETED},
 *                    {@code PARTIAL_SUCCESS}, ...)
 * @param scopeName   ACCESS only: the group name for a group-scoped grant or request, else null
 * @param actorName   who approved, denied or granted; null when the user did it themselves
 * @param note        a reviewer's decision note, or an operation's error message
 * @param referenceId the execution, request or grant id
 */
public record MyActivityItemDTO(
        String kind,
        String event,
        String status,
        String environmentId,
        String environmentName,
        String scopeName,
        String accessLevel,
        String actorName,
        Integer totalTargets,
        Integer completedTargets,
        Integer failedTargets,
        String note,
        Timestamp occurredAt,
        String referenceId
) {}
