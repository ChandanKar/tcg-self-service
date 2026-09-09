package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure-unit coverage of {@link UserService#findOrCreateUser} — the login-time adoption path
 * that a directory / manual onboard depends on (no duplicate row, email refresh).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceFindOrCreateTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, auditService);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static User existing(String userId, String oid, String email, String displayName) {
        User u = new User(userId);
        u.setAzureAdObjectId(oid);
        u.setEmail(email);
        u.setDisplayName(displayName);
        u.setIsActive(true);
        return u;
    }

    @Test
    void oidMatch_updatesDisplayNameAndLastLogin_withoutCreating() {
        User row = existing("u1", "oid-1", "a@corp.com", "Old Name");
        when(userRepository.findByAzureAdObjectId("oid-1")).thenReturn(Optional.of(row));

        User result = service.findOrCreateUser("oid-1", "a@corp.com", "New Name");

        assertSame(row, result);
        assertEquals("New Name", result.getDisplayName());
        assertNotNull(result.getLastLoginAt());
        verify(userRepository).save(row);
        verify(auditService, never()).logUserCreated(anyString(), anyString());
    }

    @Test
    void oidMatch_refreshesEmail_whenChangedAndNotHeldByAnother() {
        User row = existing("u2", "oid-2", "btwo@corp.com", "Bob Two"); // stored UPN at onboard
        when(userRepository.findByAzureAdObjectId("oid-2")).thenReturn(Optional.of(row));
        when(userRepository.findByEmail("bob.two@corp.com")).thenReturn(Optional.empty());

        User result = service.findOrCreateUser("oid-2", "bob.two@corp.com", "Bob Two");

        assertEquals("bob.two@corp.com", result.getEmail());
    }

    @Test
    void oidMatch_keepsEmail_whenNewAddressAlreadyHeldByAnotherUser() {
        User row = existing("u3", "oid-3", "orig@corp.com", "Three");
        when(userRepository.findByAzureAdObjectId("oid-3")).thenReturn(Optional.of(row));
        when(userRepository.findByEmail("taken@corp.com"))
                .thenReturn(Optional.of(existing("u-other", "oid-other", "taken@corp.com", "Other")));

        User result = service.findOrCreateUser("oid-3", "taken@corp.com", "Three");

        assertEquals("orig@corp.com", result.getEmail());
    }

    @Test
    void emailFallback_adoptsManualOnboardRow_linkingOid_withoutCreating() {
        User manualRow = existing("u4", null, "manual@corp.com", "Manual User");
        manualRow.setOnboardedBy("admin-x");
        manualRow.setOnboardedAt(new Timestamp(System.currentTimeMillis()));
        when(userRepository.findByAzureAdObjectId("oid-4")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("manual@corp.com")).thenReturn(Optional.of(manualRow));

        User result = service.findOrCreateUser("oid-4", "manual@corp.com", "Manual User");

        assertEquals("u4", result.getUserId());
        assertEquals("oid-4", result.getAzureAdObjectId());
        assertNotNull(result.getLastLoginAt());
        verify(auditService, never()).logUserCreated(anyString(), anyString());
    }

    @Test
    void noMatch_createsNewUser_withAudit() {
        when(userRepository.findByAzureAdObjectId("oid-5")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@corp.com")).thenReturn(Optional.empty());

        User result = service.findOrCreateUser("oid-5", "new@corp.com", "New User");

        assertEquals("oid-5", result.getAzureAdObjectId());
        assertEquals("new@corp.com", result.getEmail());
        assertNotNull(result.getUserId());
        verify(userRepository).save(any(User.class));
        verify(auditService).logUserCreated(anyString(), eq("new@corp.com"));
    }
}
