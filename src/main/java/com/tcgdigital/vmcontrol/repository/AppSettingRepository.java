package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
