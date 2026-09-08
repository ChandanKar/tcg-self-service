package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VmStateHistoryDTOTest {

    @Test
    void resolveUsername_prefersUsernameWhenPresent() {
        User user = new User();
        user.setUsername("chandan.kar");
        user.setEmail("chandan.kar@tcgdigital.com");

        assertEquals("chandan.kar", VmStateHistoryDTO.resolveUsername(user));
    }

    @Test
    void resolveUsername_infersFromEmailLocalPartWhenUsernameBlank() {
        User user = new User();
        user.setUsername("");
        user.setEmail("chandan.kar@tcgdigital.com");

        assertEquals("chandan.kar", VmStateHistoryDTO.resolveUsername(user));
    }

    @Test
    void resolveUsername_infersFromEmailLocalPartWhenUsernameNull() {
        User user = new User();
        user.setEmail("jane.doe@example.com");

        assertEquals("jane.doe", VmStateHistoryDTO.resolveUsername(user));
    }

    @Test
    void resolveUsername_returnsNullWhenNeitherUsernameNorEmailAvailable() {
        User user = new User();

        assertNull(VmStateHistoryDTO.resolveUsername(user));
    }

    @Test
    void resolveUsername_returnsNullForNullUser() {
        assertNull(VmStateHistoryDTO.resolveUsername(null));
    }
}
