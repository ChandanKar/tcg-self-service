package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.IdleStopMode;
import com.tcgdigital.vmcontrol.model.IdleStopRule;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** An idle auto-stop rule (E16). */
public record IdleStopRuleDTO(
        String ruleId,
        String environmentId,
        AutomationScopeType scopeType,
        String groupId,
        String groupName,
        Integer idleMinutes,
        BigDecimal cpuMaxPercent,
        BigDecimal networkMbPerDay,
        IdleStopMode mode,
        Boolean enabled,
        Timestamp dryRunStartedAt,
        String createdByUserId,
        Timestamp createdAt,
        Timestamp updatedAt
) {
    public static IdleStopRuleDTO fromEntity(IdleStopRule rule, String groupName) {
        return new IdleStopRuleDTO(rule.getRuleId(), rule.getEnvironment().getEnvironmentId(), rule.getScopeType(),
                rule.getGroupId(), groupName, rule.getIdleMinutes(), rule.getCpuMaxPercent(), rule.getNetworkMbPerDay(),
                rule.getMode(), rule.getEnabled(), rule.getDryRunStartedAt(), rule.getCreatedByUserId(),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
