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
                             boolean editable, String value, String defaultValue, String source, BigDecimal min, BigDecimal max,
                             java.sql.Timestamp updatedAt, String updatedByName) {
        static SettingDTO from(SettingsService.SettingView v, java.util.Map<String, String> names) {
            SettingDefinition d = v.definition();
            return new SettingDTO(d.key(), d.group(), d.label(), d.description(), d.type().name(), d.tier(), d.editable(),
                    v.value(), v.defaultValue(), v.source().name(), d.min(), d.max(), v.updatedAt(),
                    v.updatedByUserId() == null ? null : names.getOrDefault(v.updatedByUserId(), v.updatedByUserId()));
        }
    }

    /** Display names of whoever changed the settings, in one query. */
    private java.util.Map<String, String> names(List<SettingsService.SettingView> views) {
        List<String> ids = views.stream().map(SettingsService.SettingView::updatedByUserId).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            userRepository.findAllById(ids).forEach(u -> names.put(u.getUserId(), u.getDisplayName() != null ? u.getDisplayName() : u.getEmail()));
        }
        return names;
    }

    private SettingDTO one(SettingsService.SettingView v) {
        return SettingDTO.from(v, names(List.of(v)));
    }

    public record UpdateSettingRequest(String value) {}

    private final SettingsService settingsService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.repository.UserRepository userRepository;

    public SettingsController(SettingsService settingsService, UserService userService,
                              com.tcgdigital.vmcontrol.repository.UserRepository userRepository) {
        this.settingsService = settingsService;
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<SettingDTO> list() {
        List<SettingsService.SettingView> views = settingsService.list();
        java.util.Map<String, String> names = names(views);
        return views.stream().map(v -> SettingDTO.from(v, names)).toList();
    }

    @PutMapping("/{key:.+}")
    @PreAuthorize("hasRole('ADMIN')")
    public SettingDTO update(@PathVariable String key, @RequestBody UpdateSettingRequest request) {
        return one(settingsService.set(key, request == null ? null : request.value(), userService.getCurrentUserId()));
    }

    @DeleteMapping("/{key:.+}")
    @PreAuthorize("hasRole('ADMIN')")
    public SettingDTO reset(@PathVariable String key) {
        return one(settingsService.reset(key, userService.getCurrentUserId()));
    }
}
