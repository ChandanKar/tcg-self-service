package com.tcgdigital.vmcontrol.dto;

import java.util.List;

/**
 * Response of {@code POST /api/v1/users}: the created user, plus any grants applied from
 * {@code initialGrant} (empty when none was requested).
 */
public record OnboardUserResponseDTO(
        UserDTO user,
        List<EnvironmentAccessDTO> grants
) {}
