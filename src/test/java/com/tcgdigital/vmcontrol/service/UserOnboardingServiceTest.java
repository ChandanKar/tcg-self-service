package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AccessGrantRequestDTO;
import com.tcgdigital.vmcontrol.dto.DirectoryUserDTO;
import com.tcgdigital.vmcontrol.dto.OnboardUserDTO;
import com.tcgdigital.vmcontrol.exception.DirectoryLookupException;
import com.tcgdigital.vmcontrol.exception.UserAlreadyExistsException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserOnboardingServiceTest {

    @Mock private UserService userService;
    @Mock private EnvironmentAccessService environmentAccessService;
    @Mock private GraphDirectoryService graphDirectoryService;
    @Mock private UserRepository userRepository;

    @InjectMocks private UserOnboardingService service;

    private static final String ACTOR = "admin-1";

    private static User user(String id, String email, boolean active) {
        User u = new User(id);
        u.setEmail(email);
        u.setDisplayName(email);
        u.setIsActive(active);
        return u;
    }

    private static OnboardUserDTO dto(String directoryObjectId, String email, String displayName) {
        OnboardUserDTO d = new OnboardUserDTO();
        d.setDirectoryObjectId(directoryObjectId);
        d.setEmail(email);
        d.setDisplayName(displayName);
        return d;
    }

    @Test
    void directoryPath_trustsGraphData_createsUserWithOid() {
        when(graphDirectoryService.isEnabled()).thenReturn(true);
        when(graphDirectoryService.fetchByObjectId("oid-x"))
                .thenReturn(new DirectoryUserDTO("oid-x", "Real Name", "real@corp.com", "real@corp.com", false, null));
        when(userRepository.findByAzureAdObjectId("oid-x")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("real@corp.com")).thenReturn(Optional.empty());
        User created = user("new-1", "real@corp.com", true);
        when(userService.createOnboardedUser("real@corp.com", "Real Name", "oid-x", false, false, ACTOR))
                .thenReturn(created);

        UserOnboardingService.OnboardResult result = service.onboard(
                dto("oid-x", "ignored@x.com", "Ignored"), ACTOR);

        assertSame(created, result.user());
        assertTrue(result.grants().isEmpty());
        verify(userService).createOnboardedUser("real@corp.com", "Real Name", "oid-x", false, false, ACTOR);
        verifyNoInteractions(environmentAccessService);
    }

    @Test
    void directoryObjectIdButLookupDisabled_fallsBackToManualUnverified() {
        when(graphDirectoryService.isEnabled()).thenReturn(false);
        when(userRepository.findByEmail("typed@corp.com")).thenReturn(Optional.empty());
        when(userService.createOnboardedUser(eq("typed@corp.com"), eq("Typed"), isNull(),
                eq(false), eq(false), eq(ACTOR))).thenReturn(user("new-2", "typed@corp.com", true));

        service.onboard(dto("oid-x", "typed@corp.com", "Typed"), ACTOR);

        verify(graphDirectoryService, never()).fetchByObjectId(any());
        verify(userService).createOnboardedUser("typed@corp.com", "Typed", null, false, false, ACTOR);
    }

    @Test
    void manualPath_missingEmail_throwsValidation_andCreatesNothing() {
        assertThrows(ValidationException.class,
                () -> service.onboard(dto(null, "  ", null), ACTOR));

        verifyNoInteractions(userService);
        verifyNoInteractions(environmentAccessService);
    }

    @Test
    void manualPath_displayNameDefaultsToEmailLocalPart() {
        when(userRepository.findByEmail("jane.doe@corp.com")).thenReturn(Optional.empty());
        when(userService.createOnboardedUser(eq("jane.doe@corp.com"), eq("jane.doe"), isNull(),
                anyBoolean(), anyBoolean(), eq(ACTOR))).thenReturn(user("new-3", "jane.doe@corp.com", true));

        service.onboard(dto(null, "jane.doe@corp.com", null), ACTOR);

        verify(userService).createOnboardedUser("jane.doe@corp.com", "jane.doe", null, false, false, ACTOR);
    }

    @Test
    void duplicateActiveUserByEmail_throwsConflict_withExistingId() {
        when(userRepository.findByEmail("dup@corp.com"))
                .thenReturn(Optional.of(user("existing-1", "dup@corp.com", true)));

        UserAlreadyExistsException ex = assertThrows(UserAlreadyExistsException.class,
                () -> service.onboard(dto(null, "dup@corp.com", "Dup"), ACTOR));

        assertEquals("existing-1", ex.getUserId());
        assertTrue(ex.isActive());
        verify(userService, never()).createOnboardedUser(any(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void duplicateInactiveUserByOid_throwsConflict_markedInactive() {
        when(graphDirectoryService.isEnabled()).thenReturn(true);
        when(graphDirectoryService.fetchByObjectId("oid-y"))
                .thenReturn(new DirectoryUserDTO("oid-y", "Y", "y@corp.com", "y@corp.com", false, null));
        when(userRepository.findByAzureAdObjectId("oid-y"))
                .thenReturn(Optional.of(user("u2", "y@corp.com", false)));

        UserAlreadyExistsException ex = assertThrows(UserAlreadyExistsException.class,
                () -> service.onboard(dto("oid-y", null, null), ACTOR));

        assertEquals("u2", ex.getUserId());
        assertFalse(ex.isActive());
    }

    @Test
    void directoryUserWithNoEmailOrUpn_throwsValidation() {
        when(graphDirectoryService.isEnabled()).thenReturn(true);
        when(graphDirectoryService.fetchByObjectId("oid-z"))
                .thenReturn(new DirectoryUserDTO("oid-z", "Z", null, null, false, null));

        assertThrows(ValidationException.class, () -> service.onboard(dto("oid-z", null, null), ACTOR));
        verifyNoInteractions(userService);
    }

    @Test
    void withInitialGrant_delegatesToGrantScoped_withOnboardedUsersEmail() {
        when(userRepository.findByEmail("grantee@corp.com")).thenReturn(Optional.empty());
        when(userService.createOnboardedUser(eq("grantee@corp.com"), any(), isNull(),
                anyBoolean(), anyBoolean(), eq(ACTOR))).thenReturn(user("new-9", "grantee@corp.com", true));
        when(environmentAccessService.grantScoped(eq(ACTOR), any(AccessGrantRequestDTO.class)))
                .thenReturn(List.of(mock(EnvironmentAccess.class)));

        OnboardUserDTO d = dto(null, "grantee@corp.com", "Grantee");
        OnboardUserDTO.InitialGrant g = new OnboardUserDTO.InitialGrant();
        g.setEnvironmentId("env-A");
        g.setAccessLevel(AccessLevel.USER);
        g.setScopeType(AccessScopeType.ENVIRONMENT);
        g.setDurationDays(30);
        d.setInitialGrant(g);

        UserOnboardingService.OnboardResult result = service.onboard(d, ACTOR);

        assertEquals(1, result.grants().size());
        ArgumentCaptor<AccessGrantRequestDTO> captor = ArgumentCaptor.forClass(AccessGrantRequestDTO.class);
        verify(environmentAccessService).grantScoped(eq(ACTOR), captor.capture());
        AccessGrantRequestDTO sent = captor.getValue();
        assertEquals("grantee@corp.com", sent.getUserEmail());
        assertEquals("env-A", sent.getEnvironmentId());
        assertEquals(AccessLevel.USER, sent.getAccessLevel());
        assertEquals(AccessScopeType.ENVIRONMENT, sent.getScopeType());
        assertEquals(30, sent.getDurationDays());
    }

    // ---- onboardAndGrant: backs POST /api/v1/access-grants with a directoryObjectId ----

    @Test
    void onboardAndGrant_directoryDisabled_throwsDisabled_andCreatesNothing() {
        when(graphDirectoryService.isEnabled()).thenReturn(false);

        AccessGrantRequestDTO grant = new AccessGrantRequestDTO();
        grant.setEnvironmentId("env-A");
        grant.setAccessLevel(AccessLevel.USER);
        grant.setScopeType(AccessScopeType.ENVIRONMENT);

        assertThrows(DirectoryLookupException.class,
                () -> service.onboardAndGrant("oid-g", grant, ACTOR));

        verifyNoInteractions(userService);
        verifyNoInteractions(environmentAccessService);
        verify(graphDirectoryService, never()).fetchByObjectId(any());
    }

    @Test
    void onboardAndGrant_onboardsAsNormalUser_thenGrantsWithScopeAndNotes() {
        when(graphDirectoryService.isEnabled()).thenReturn(true);
        when(graphDirectoryService.fetchByObjectId("oid-g"))
                .thenReturn(new DirectoryUserDTO("oid-g", "Priya Nair", "priya.nair@corp.com",
                        "priya.nair@corp.com", false, null));
        when(userRepository.findByAzureAdObjectId("oid-g")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("priya.nair@corp.com")).thenReturn(Optional.empty());
        when(userService.createOnboardedUser("priya.nair@corp.com", "Priya Nair", "oid-g", false, false, ACTOR))
                .thenReturn(user("new-g", "priya.nair@corp.com", true));
        when(environmentAccessService.grantScoped(eq(ACTOR), any(AccessGrantRequestDTO.class)))
                .thenReturn(List.of(mock(EnvironmentAccess.class)));

        AccessGrantRequestDTO grant = new AccessGrantRequestDTO();
        grant.setEnvironmentId("env-A");
        grant.setAccessLevel(AccessLevel.USER);
        grant.setScopeType(AccessScopeType.GROUP);
        grant.setGroupIds(List.of("grp-1", "grp-2"));
        grant.setDurationDays(30);
        grant.setNotes("temp cover for release");

        UserOnboardingService.OnboardResult result = service.onboardAndGrant("oid-g", grant, ACTOR);

        assertEquals(1, result.grants().size());
        // onboarded with no admin / env-admin rights
        verify(userService).createOnboardedUser("priya.nair@corp.com", "Priya Nair", "oid-g", false, false, ACTOR);

        ArgumentCaptor<AccessGrantRequestDTO> captor = ArgumentCaptor.forClass(AccessGrantRequestDTO.class);
        verify(environmentAccessService).grantScoped(eq(ACTOR), captor.capture());
        AccessGrantRequestDTO sent = captor.getValue();
        assertEquals("priya.nair@corp.com", sent.getUserEmail());
        assertEquals("env-A", sent.getEnvironmentId());
        assertEquals(AccessLevel.USER, sent.getAccessLevel());
        assertEquals(AccessScopeType.GROUP, sent.getScopeType());
        assertEquals(List.of("grp-1", "grp-2"), sent.getGroupIds());
        assertEquals(30, sent.getDurationDays());
        assertEquals("temp cover for release", sent.getNotes());
    }
}
