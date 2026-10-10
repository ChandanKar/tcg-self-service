package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.AccountConflictException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        service = new UserService(userRepository, auditService, null);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
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

    // ---- E03-T08: no takeover by email; initial admin only bootstraps --------------------

    @Test
    void emailFallback_refusesRowLinkedToAnotherDirectoryAccount() {
        User linked = existing("u6", "oid-X", "shared@corp.com", "Original Owner");
        when(userRepository.findByAzureAdObjectId("oid-Y")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("shared@corp.com")).thenReturn(Optional.of(linked));

        AccountConflictException e = assertThrows(AccountConflictException.class,
                () -> service.findOrCreateUser("oid-Y", "shared@corp.com", "Someone Else"));

        assertEquals("u6", e.getExistingUserId());
        assertEquals("oid-X", linked.getAzureAdObjectId(), "the existing link is untouched");
        assertEquals("Original Owner", linked.getDisplayName());
        verify(userRepository, never()).save(any(User.class));
        verify(auditService).logLoginConflict("shared@corp.com", "u6");
    }

    @Test
    void initialAdmin_isPromotedOnlyWhileNoActiveAdminExists() {
        ReflectionTestUtils.setField(service, "initialAdminEmail", "boss@corp.com");
        when(userRepository.findByAzureAdObjectId("oid-boss")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("boss@corp.com")).thenReturn(Optional.empty());
        when(userRepository.countByAdminTrueAndIsActiveTrue()).thenReturn(0L);

        User created = service.findOrCreateUser("oid-boss", "boss@corp.com", "Boss");

        assertTrue(created.isAdmin());
        verify(auditService).logUserRoleChanged(eq("system"), anyString(), eq("admin"), eq(true));
    }

    @Test
    void initialAdmin_isNotPromotedWhenAnotherAdminExists() {
        ReflectionTestUtils.setField(service, "initialAdminEmail", "boss@corp.com");
        when(userRepository.findByAzureAdObjectId("oid-boss")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("boss@corp.com")).thenReturn(Optional.empty());
        when(userRepository.countByAdminTrueAndIsActiveTrue()).thenReturn(1L);

        User created = service.findOrCreateUser("oid-boss", "boss@corp.com", "Boss");

        assertFalse(created.isAdmin());
        verify(auditService, never()).logUserRoleChanged(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void initialAdmin_whoWasDemoted_staysDemotedOnNextLogin() {
        ReflectionTestUtils.setField(service, "initialAdminEmail", "boss@corp.com");
        User demoted = existing("u7", "oid-boss", "boss@corp.com", "Boss");
        demoted.setAdmin(false);
        when(userRepository.findByAzureAdObjectId("oid-boss")).thenReturn(Optional.of(demoted));
        when(userRepository.countByAdminTrueAndIsActiveTrue()).thenReturn(2L);

        User result = service.findOrCreateUser("oid-boss", "boss@corp.com", "Boss");

        assertFalse(result.isAdmin());
    }
}
