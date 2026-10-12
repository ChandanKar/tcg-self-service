package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.EnvironmentTemplateDTO;
import com.tcgdigital.vmcontrol.dto.UpsertEnvironmentTemplateDTO;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.provisioning.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The environment template catalogue (E32-T01); 404 everywhere while provisioning is off. */
@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "Templates", description = "Approved environment templates")
public class TemplateController {

    private final TemplateService templateService;
    private final UserService userService;

    public TemplateController(TemplateService templateService, UserService userService) {
        this.templateService = templateService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List templates", description = "Active templates the caller's role may request; admins may add includeRetired=true.")
    public ResponseEntity<List<EnvironmentTemplateDTO>> list(@RequestParam(defaultValue = "false") boolean includeRetired) {
        return ResponseEntity.ok(templateService.list(includeRetired));
    }

    /** Every template, retired ones too, with engine references: the admin editor's list (E32-T08). */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<EnvironmentTemplateDTO>> all() {
        return ResponseEntity.ok(templateService.list(true));
    }

    @GetMapping("/{templateId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EnvironmentTemplateDTO> get(@PathVariable String templateId) {
        return ResponseEntity.ok(templateService.get(templateId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a template")
    public ResponseEntity<EnvironmentTemplateDTO> create(@Valid @RequestBody UpsertEnvironmentTemplateDTO dto) {
        templateService.requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(dto, userService.getCurrentUserId()));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change a template")
    public ResponseEntity<EnvironmentTemplateDTO> update(@PathVariable String templateId,
                                                         @Valid @RequestBody UpsertEnvironmentTemplateDTO dto) {
        return ResponseEntity.ok(templateService.update(templateId, dto, userService.getCurrentUserId()));
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Retire a template", description = "It stays for existing requests but cannot be requested again.")
    public ResponseEntity<EnvironmentTemplateDTO> retire(@PathVariable String templateId) {
        return ResponseEntity.ok(templateService.retire(templateId, userService.getCurrentUserId()));
    }
}
