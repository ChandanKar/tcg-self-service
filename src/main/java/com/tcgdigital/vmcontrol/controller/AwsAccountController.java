package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.AwsAccountDTO;
import com.tcgdigital.vmcontrol.dto.ImportAwsAccountsDTO;
import com.tcgdigital.vmcontrol.dto.OrganizationAccountCandidateDTO;
import com.tcgdigital.vmcontrol.dto.UpsertAwsAccountDTO;
import com.tcgdigital.vmcontrol.service.UserService;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The AWS account registry (E28-T07): list, register, edit, deactivate and verify. Admins only. */
@RestController
@RequestMapping("/api/v1/aws-accounts")
public class AwsAccountController {

    private final AwsAccountAdminService adminService;
    private final UserService userService;
    private final com.tcgdigital.vmcontrol.service.aws.AwsAccountService awsAccountService;

    public AwsAccountController(AwsAccountAdminService adminService, UserService userService,
                                com.tcgdigital.vmcontrol.service.aws.AwsAccountService awsAccountService) {
        this.adminService = adminService;
        this.userService = userService;
        this.awsAccountService = awsAccountService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AwsAccountDTO> list() {
        return adminService.list();
    }

    /** Active accounts to choose from in environment and VM forms (E28-T10). */
    @GetMapping("/options")
    @PreAuthorize("hasAnyRole('ADMIN','ENV_ADMIN')")
    public List<java.util.Map<String, Object>> options() {
        return awsAccountService.listActive().stream()
                .map(a -> java.util.Map.<String, Object>of("ref", a.getAwsAccountRef(), "name", a.getDisplayName(),
                        "regions", a.getRegionList(), "isDefault", a.isDefault()))
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AwsAccountDTO> create(@Valid @RequestBody UpsertAwsAccountDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.create(userService.getCurrentUserId(), request));
    }

    /** Member accounts of the AWS organization (E28-T08); 400 when discovery is off or not possible. */
    @GetMapping("/organization-candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public List<OrganizationAccountCandidateDTO> organizationCandidates() {
        return adminService.listOrganizationCandidates();
    }

    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportAwsAccountsDTO.Result importAccounts(@Valid @RequestBody ImportAwsAccountsDTO.Request request) {
        return adminService.importAccounts(userService.getCurrentUserId(), request);
    }

    @PutMapping("/{ref}")
    @PreAuthorize("hasRole('ADMIN')")
    public AwsAccountDTO update(@PathVariable String ref, @Valid @RequestBody UpsertAwsAccountDTO request) {
        return adminService.update(userService.getCurrentUserId(), ref, request);
    }

    @DeleteMapping("/{ref}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable String ref) {
        adminService.deactivate(userService.getCurrentUserId(), ref);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{ref}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public AwsAccountDTO verify(@PathVariable String ref) {
        return adminService.verify(userService.getCurrentUserId(), ref);
    }
}
