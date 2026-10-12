package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmResizeEvent;

import java.sql.Timestamp;

/** A safe resize as the Apply dialog follows it (E30-T06). */
public record ResizeStatusDTO(
        String resizeId,
        String vmId,
        String oldInstanceType,
        String newInstanceType,
        String status,
        String stage,
        String errorMessage,
        boolean finished,
        boolean restartRequested,
        String originalStatus,
        Timestamp updatedAt
) {
    public static ResizeStatusDTO of(VmResizeEvent e) {
        return new ResizeStatusDTO(e.getEventId(), e.getVmId(), e.getOldInstanceType(), e.getNewInstanceType(),
                e.getStatus().name(), e.getStage(), e.getErrorMessage(), e.getStatus().isFinal(), e.isRestartRequested(),
                e.getOriginalStatus(), e.getUpdatedAt());
    }
}
