package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Move a VM to another group of the same environment, e.g. out of Auto-Discovered (M34).
 */
public class MoveVmDTO {

    @NotBlank(message = "Target group ID is required")
    private String targetGroupId;

    /** Optional; used if free in the target group, otherwise the next free position. */
    @Min(value = 1, message = "Sequence position must be at least 1")
    private Integer sequencePosition;

    public String getTargetGroupId() {
        return targetGroupId;
    }

    public void setTargetGroupId(String targetGroupId) {
        this.targetGroupId = targetGroupId;
    }

    public Integer getSequencePosition() {
        return sequencePosition;
    }

    public void setSequencePosition(Integer sequencePosition) {
        this.sequencePosition = sequencePosition;
    }
}
