package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.IdleStopMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

/** Create or update an idle auto-stop rule (E16). Omitted thresholds take the defaults. */
public class IdleStopRuleRequestDTO {

    /** ENVIRONMENT (default) or GROUP. */
    private AutomationScopeType scopeType = AutomationScopeType.ENVIRONMENT;

    /** Required for GROUP scope; a group of the same environment. */
    private String groupId;

    @Min(value = 30, message = "Idle minutes must be at least 30")
    @Max(value = 1440, message = "Idle minutes must be at most 1440")
    private Integer idleMinutes = 60;

    @DecimalMin(value = "0.5", message = "CPU threshold must be at least 0.5%")
    @DecimalMax(value = "20", message = "CPU threshold must be at most 20%")
    private BigDecimal cpuMaxPercent = new BigDecimal("5.00");

    @DecimalMin(value = "0.1", message = "Network threshold must be at least 0.1 MB/day")
    @DecimalMax(value = "1000", message = "Network threshold must be at most 1000 MB/day")
    private BigDecimal networkMbPerDay = new BigDecimal("5.00");

    /** Ignored on create (new rules start in DRY_RUN). */
    private IdleStopMode mode;

    private Boolean enabled = true;

    public AutomationScopeType getScopeType() { return scopeType; }
    public void setScopeType(AutomationScopeType scopeType) { this.scopeType = scopeType; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public Integer getIdleMinutes() { return idleMinutes; }
    public void setIdleMinutes(Integer idleMinutes) { this.idleMinutes = idleMinutes; }
    public BigDecimal getCpuMaxPercent() { return cpuMaxPercent; }
    public void setCpuMaxPercent(BigDecimal cpuMaxPercent) { this.cpuMaxPercent = cpuMaxPercent; }
    public BigDecimal getNetworkMbPerDay() { return networkMbPerDay; }
    public void setNetworkMbPerDay(BigDecimal networkMbPerDay) { this.networkMbPerDay = networkMbPerDay; }
    public IdleStopMode getMode() { return mode; }
    public void setMode(IdleStopMode mode) { this.mode = mode; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
