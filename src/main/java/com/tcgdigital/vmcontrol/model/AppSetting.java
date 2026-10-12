package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;

/** An admin's override of a runtime setting (E29-T01); without a row the property file's value applies. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 150)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    private String settingValue;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @Column(name = "updated_by_user_id", length = 36)
    private String updatedByUserId;

    public AppSetting() {
    }

    public AppSetting(String settingKey, String settingValue, String updatedByUserId) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.updatedByUserId = updatedByUserId;
    }

    public String getSettingKey() { return settingKey; }
    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
