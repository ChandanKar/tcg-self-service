package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AutomationRuleOverride.SkipAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response shapes of rule overrides (E19-T03). */
public final class AutomationRuleOverrideDTOs {

    private AutomationRuleOverrideDTOs() {
    }

    public record OverrideRequest(
            @NotNull(message = "Start is required") Instant startsAt,
            @NotNull(message = "End is required") Instant endsAt,
            @NotNull(message = "Choose which actions to skip") SkipAction skipAction,
            @NotBlank(message = "A reason is required") @Size(max = 255, message = "Reason must be at most 255 characters") String reason) {}

    public record OverrideDTO(String overrideId, String ruleId, Instant startsAt, Instant endsAt, SkipAction skipAction,
                              String reason, String createdByUserId, String createdByDisplayName, boolean active) {}
}
