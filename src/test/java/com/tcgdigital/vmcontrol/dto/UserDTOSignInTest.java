package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** U05-T05: user lists say how each person signs in. */
class UserDTOSignInTest {

    private static User user(String objectId, String password, boolean serviceAccount) {
        User u = new User();
        u.setUserId("u-1");
        u.setEmail("priya@example.com");
        u.setAzureAdObjectId(objectId);
        u.setPassword(password);
        u.setServiceAccount(serviceAccount);
        return u;
    }

    @Test
    void linkedToEntraWithoutAPasswordSignsInWithMicrosoft() {
        UserDTO dto = UserDTO.fromEntity(user("oid-1", null, false));
        assertThat(dto.getSignInMethod()).isEqualTo("MICROSOFT");
        assertThat(dto.isSignsInWithMicrosoft()).isTrue();
    }

    @Test
    void aPasswordAccountWithoutEntraSignsInWithAPassword() {
        UserDTO dto = UserDTO.fromEntity(user(null, "$2a$10$hash", false));
        assertThat(dto.getSignInMethod()).isEqualTo("PASSWORD");
        assertThat(dto.isSignsInWithMicrosoft()).isFalse();
    }

    @Test
    void bothWaysWhenLinkedAndAPasswordIsSet() {
        UserDTO dto = UserDTO.fromEntity(user("oid-1", "$2a$10$hash", false));
        assertThat(dto.getSignInMethod()).isEqualTo("MICROSOFT_AND_PASSWORD");
        assertThat(dto.isSignsInWithMicrosoft()).isTrue();
    }

    @Test
    void onboardedByHandWithoutAPasswordIsExpectedToUseMicrosoft() {
        UserDTO dto = UserDTO.fromEntity(user(null, "  ", false));
        assertThat(dto.getSignInMethod()).isEqualTo("MICROSOFT");
        assertThat(dto.isSignsInWithMicrosoft()).isTrue();
    }

    @Test
    void aServiceAccountDoesNotSignIn() {
        UserDTO dto = UserDTO.fromEntity(user(null, null, true));
        assertThat(dto.getSignInMethod()).isEqualTo("NONE");
        assertThat(dto.isSignsInWithMicrosoft()).isFalse();
    }
}
