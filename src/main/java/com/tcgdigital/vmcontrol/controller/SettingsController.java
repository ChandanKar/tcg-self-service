package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.settings.SettingDefinition;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** Runtime settings for admins (E29-T01): definitions with their effective value; tier 1 can be changed or reset. */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SettingsController {

    /** One setting as the page shows it. */
    public record SettingDTO(String key, String group, String label, String description, String type, int tier,
                             boolean editable, String value, String defaultValue, String source, BigDecimal min, BigDecimal max) {
        static SettingDTO from(SettingsService.SettingView v) {
            SettingDefinition d = v.definition();
            return new SettingDTO(d.key(), d.group(), d.label(), d.description(), d.type().name(), d.tier(), d.editable(),
                    v.value(), v.defaultValue(), v.source().name(), d.min(), d.max());
        }
    }

    public record UpdateSettingRequest(String value) {}

    private final SettingsService settingsService;
    private final UserService userService;

    public SettingsController(SettingsService settingsService, UserService userService) {
        this.settingsService = settingsService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<SettingDTO> list() {
        return settingsService.list().stream().map(SettingDTO::from).toList();
    }

    @PutMapping("/{key:.+}")
    @PreAuthorize("hasRole('ADMIN')")
    public SettingDTO update(@PathVariable String key, @RequestBody UpdateSettingRequest request) {
        return SettingDTO.from(settingsService.set(key, request == null ? null : request.value(), userService.getCurrentUserId()));
    }

    @DeleteMapping("/{key:.+}")
    @PreAuthorize("hasRole('ADMIN')")
    public SettingDTO reset(@PathVariable String key) {
        return SettingDTO.from(settingsService.reset(key, userService.getCurrentUserId()));
    }
}
