package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotNull;

/**
 * An environment's lease policy (E15-T01). {@code enabled}, {@code defaultMinutes} and
 * {@code maxMinutes} are written by owners (null minutes = platform value); the platform limits,
 * the effective values and {@code canEdit} are read-only.
 */
public class LeasePolicyDTO {

    @NotNull
    private Boolean enabled;
    private Integer defaultMinutes;
    private Integer maxMinutes;

    private Integer effectiveDefaultMinutes;
    private Integer effectiveMaxMinutes;
    private Integer platformDefaultMinutes;
    private Integer platformMaxMinutes;
    private Integer platformAbsoluteMaxMinutes;
    private boolean canEdit;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getDefaultMinutes() { return defaultMinutes; }
    public void setDefaultMinutes(Integer defaultMinutes) { this.defaultMinutes = defaultMinutes; }
    public Integer getMaxMinutes() { return maxMinutes; }
    public void setMaxMinutes(Integer maxMinutes) { this.maxMinutes = maxMinutes; }
    public Integer getEffectiveDefaultMinutes() { return effectiveDefaultMinutes; }
    public void setEffectiveDefaultMinutes(Integer v) { this.effectiveDefaultMinutes = v; }
    public Integer getEffectiveMaxMinutes() { return effectiveMaxMinutes; }
    public void setEffectiveMaxMinutes(Integer v) { this.effectiveMaxMinutes = v; }
    public Integer getPlatformDefaultMinutes() { return platformDefaultMinutes; }
    public void setPlatformDefaultMinutes(Integer v) { this.platformDefaultMinutes = v; }
    public Integer getPlatformMaxMinutes() { return platformMaxMinutes; }
    public void setPlatformMaxMinutes(Integer v) { this.platformMaxMinutes = v; }
    public Integer getPlatformAbsoluteMaxMinutes() { return platformAbsoluteMaxMinutes; }
    public void setPlatformAbsoluteMaxMinutes(Integer v) { this.platformAbsoluteMaxMinutes = v; }
    public boolean isCanEdit() { return canEdit; }
    public void setCanEdit(boolean canEdit) { this.canEdit = canEdit; }
}
