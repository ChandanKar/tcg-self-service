package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.service.support.NameSanitizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * "Add VMs to &lt;environment&gt;" (U11-T01): several picked instances into one group, after its current
 * VMs. Either {@code groupId} (a group of the environment) or {@code newGroupName} (made at the end of
 * the start order). Each item is a reference from the candidate list and the VM's name; the
 * reference carries the provider, account and region.
 */
public record BatchRegisterVmsDTO(
        String groupId,
        @Size(max = 100, message = "The group name must be at most 100 characters")
        @Pattern(regexp = NameSanitizer.SAFE_NAME_REGEX, message = NameSanitizer.SAFE_NAME_MESSAGE)
        String newGroupName,
        @NotEmpty(message = "Pick at least one instance")
        @Size(max = 50, message = "Add at most 50 VMs at a time")
        List<@Valid Item> items) {

    public record Item(
            @NotBlank(message = "Every VM needs its instance from the list")
            String ref,
            @NotBlank(message = "Every VM needs a name")
            @Size(min = 2, max = 255, message = "A VM name must be between 2 and 255 characters")
            @Pattern(regexp = NameSanitizer.SAFE_NAME_REGEX, message = NameSanitizer.SAFE_NAME_MESSAGE)
            String name) {
    }
}
