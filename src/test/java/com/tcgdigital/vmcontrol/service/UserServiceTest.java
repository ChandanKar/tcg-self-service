package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for UserService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Clean up any existing test users
        userRepository.deleteAll();

        // Create a test user
        testUser = User.fromAzureAd("test-oid-123", "test@example.com", "Test User");
        testUser = userRepository.save(testUser);
    }

    @Test
    @DisplayName("Should create new user on first OAuth2 login")
    void findOrCreateUser_createsNewUser() {
        // When
        User user = userService.findOrCreateUser("new-oid-456", "newuser@example.com", "New User");

        // Then
        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("newuser@example.com");
        assertThat(user.getDisplayName()).isEqualTo("New User");
        assertThat(user.getAzureAdObjectId()).isEqualTo("new-oid-456");
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.isEnvAdmin()).isFalse();
        assertThat(user.isActive()).isTrue();
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("Should return existing user on subsequent OAuth2 login")
    void findOrCreateUser_returnsExistingUser() {
        // When
        User user = userService.findOrCreateUser("test-oid-123", "test@example.com", "Test User Updated");

        // Then
        assertThat(user.getUserId()).isEqualTo(testUser.getUserId());
        assertThat(user.getDisplayName()).isEqualTo("Test User Updated"); // Name should be updated
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("Should get user by ID")
    void getUserById_returnsUser() {
        // When
        User user = userService.getUserById(testUser.getUserId());

        // Then
        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isEqualTo(testUser.getUserId());
    }

    @Test
    @DisplayName("Should throw exception for non-existent user")
    void getUserById_throwsForNonExistent() {
        assertThatThrownBy(() -> userService.getUserById("non-existent-id"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Should get all active users")
    void getAllActiveUsers_returnsActiveOnly() {
        // Given - create an inactive user
        User inactiveUser = User.fromAzureAd("inactive-oid", "inactive@example.com", "Inactive User");
        inactiveUser.setIsActive(false);
        userRepository.save(inactiveUser);

        // When
        List<User> activeUsers = userService.getAllActiveUsers();

        // Then
        assertThat(activeUsers).hasSize(1);
        assertThat(activeUsers.get(0).getUserId()).isEqualTo(testUser.getUserId());
    }

    @Test
    @DisplayName("Should toggle admin role")
    void toggleAdmin_togglesRole() {
        // Given
        assertThat(testUser.isAdmin()).isFalse();

        // When - toggle on
        User updated = userService.toggleAdmin(testUser.getUserId(), testUser.getUserId());

        // Then
        assertThat(updated.isAdmin()).isTrue();

        // When - toggle off
        // First add another admin so we're not removing the last one
        User anotherAdmin = User.fromAzureAd("admin-oid", "admin@example.com", "Admin User");
        anotherAdmin.setAdmin(true);
        userRepository.save(anotherAdmin);

        updated = userService.toggleAdmin(testUser.getUserId(), testUser.getUserId());

        // Then
        assertThat(updated.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("Should toggle env admin role")
    void toggleEnvAdmin_togglesRole() {
        // Given
        assertThat(testUser.isEnvAdmin()).isFalse();

        // When
        User updated = userService.toggleEnvAdmin(testUser.getUserId(), testUser.getUserId());

        // Then
        assertThat(updated.isEnvAdmin()).isTrue();

        // When
        updated = userService.toggleEnvAdmin(testUser.getUserId(), testUser.getUserId());

        // Then
        assertThat(updated.isEnvAdmin()).isFalse();
    }

    @Test
    @DisplayName("Should prevent removing last admin")
    void toggleAdmin_preventsRemovingLastAdmin() {
        // Given - make test user the only admin
        testUser.setAdmin(true);
        userRepository.save(testUser);

        // When/Then
        assertThatThrownBy(() -> userService.toggleAdmin(testUser.getUserId(), testUser.getUserId()))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("last admin");
    }

    @Test
    @DisplayName("Should deactivate user")
    void deactivateUser_setsInactive() {
        // Given - create another user to perform the action
        User admin = User.fromAzureAd("admin-oid", "admin@example.com", "Admin User");
        admin.setAdmin(true);
        admin = userRepository.save(admin);

        // When
        User deactivated = userService.deactivateUser(testUser.getUserId(), admin.getUserId());

        // Then
        assertThat(deactivated.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should prevent self-deactivation")
    void deactivateUser_preventsSelfDeactivation() {
        assertThatThrownBy(() -> userService.deactivateUser(testUser.getUserId(), testUser.getUserId()))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("your own account");
    }

    @Test
    @DisplayName("Should reactivate user")
    void reactivateUser_setsActive() {
        // Given
        testUser.setIsActive(false);
        userRepository.save(testUser);

        User admin = User.fromAzureAd("admin-oid", "admin@example.com", "Admin User");
        admin.setAdmin(true);
        admin = userRepository.save(admin);

        // When
        User reactivated = userService.reactivateUser(testUser.getUserId(), admin.getUserId());

        // Then
        assertThat(reactivated.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should search users by email")
    void searchUsers_findsByEmail() {
        // When
        List<User> results = userService.searchUsers("test@");

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getEmail()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("Should search users by display name")
    void searchUsers_findsByDisplayName() {
        // When
        List<User> results = userService.searchUsers("Test User");

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getDisplayName()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("Should get user by Azure AD object ID")
    void getUserByAzureAdObjectId_returnsUser() {
        // When
        Optional<User> result = userService.getUserByAzureAdObjectId("test-oid-123");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(testUser.getUserId());
    }
}

