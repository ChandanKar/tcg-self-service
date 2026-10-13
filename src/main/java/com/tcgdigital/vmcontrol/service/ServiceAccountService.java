package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.ApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreatedApiTokenDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ApiTokenRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Service accounts (E34-T03): users for CI and automation that never sign in, act only through
 * tokens an admin issues, and name the person responsible. Their role is USER or ENV_ADMIN, never
 * ADMIN; environments are granted to them like to anyone. Deactivating one revokes its tokens.
 */
@Service
public class ServiceAccountService {

    public static final String EMAIL_DOMAIN = "@service.local";
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,59}$");

    private final UserRepository userRepository;
    private final ApiTokenRepository tokenRepository;
    private final ApiTokenService tokenService;
    private final AuditService auditService;

    public ServiceAccountService(UserRepository userRepository, ApiTokenRepository tokenRepository,
                                 ApiTokenService tokenService, AuditService auditService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    /** A service account and what an admin needs to see about it. */
    public record ServiceAccountView(String userId, String name, String email, String role, String ownerUserId,
                                     String ownerName, boolean active, Timestamp createdAt, long activeTokens) {
    }

    public record CreateRequest(String name, String role, String ownerUserId) {
    }

    @Transactional(readOnly = true)
    public List<ServiceAccountView> list() {
        tokenService.requireEnabled();
        Timestamp now = Timestamp.from(Instant.now());
        return userRepository.findByServiceAccountTrueOrderByEmail().stream().map(u -> view(u, now)).toList();
    }

    @Transactional
    public ServiceAccountView create(CreateRequest request, String actorUserId) {
        tokenService.requireEnabled();
        String name = request == null || request.name() == null ? "" : request.name().trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new ValidationException("Name must be 2 to 60 lower-case letters, digits or '-', starting with a letter or digit");
        }
        String role = request.role() == null ? "USER" : request.role().trim().toUpperCase(Locale.ROOT);
        if (!role.equals("USER") && !role.equals("ENV_ADMIN")) {
            throw new ValidationException("A service account's role is USER or ENV_ADMIN");
        }
        User owner = request.ownerUserId() == null ? null : userRepository.findById(request.ownerUserId())
                .filter(u -> u.isActive() && !u.isServiceAccount()).orElse(null);
        if (owner == null) {
            throw new ValidationException("The owner must be an active person");
        }
        String email = name + EMAIL_DOMAIN;
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ValidationException("A service account named '" + name + "' already exists");
        }
        User account = new User();
        account.setUserId(java.util.UUID.randomUUID().toString());
        account.setEmail(email);
        account.setDisplayName("Service: " + name);
        account.setAdmin(false);
        account.setEnvAdmin(role.equals("ENV_ADMIN"));
        account.setIsActive(true);
        account.setServiceAccount(true);
        account.setServiceOwnerUserId(owner.getUserId());
        userRepository.saveAndFlush(account);
        auditService.logAction(actorUserId, AuditAction.SERVICE_ACCOUNT_CREATED, "USER", account.getUserId(), email,
                "Role " + role + ", owner " + owner.getEmail());
        return view(account, Timestamp.from(Instant.now()));
    }

    /** Deactivates the account and revokes every token it holds. */
    @Transactional
    public ServiceAccountView deactivate(String userId, String actorUserId) {
        tokenService.requireEnabled();
        User account = account(userId);
        account.setIsActive(false);
        userRepository.saveAndFlush(account);
        int revoked = tokenRepository.revokeAllOf(userId, actorUserId, Timestamp.from(Instant.now()));
        auditService.logAction(actorUserId, AuditAction.SERVICE_ACCOUNT_DEACTIVATED, "USER", userId, account.getEmail(),
                revoked + " token(s) revoked");
        return view(account, Timestamp.from(Instant.now()));
    }

    public List<ApiTokenDTO> tokens(String userId) {
        account(userId);
        return tokenService.list(userId);
    }

    /** Issues a token for an active service account; its scopes follow the account's role. */
    public CreatedApiTokenDTO issueToken(String userId, CreateApiTokenDTO dto, String actorUserId) {
        User account = account(userId);
        if (!account.isActive()) {
            throw new ValidationException("The service account is deactivated");
        }
        return tokenService.create(account, dto, actorUserId);
    }

    public void revokeToken(String userId, String tokenId, String actorUserId) {
        account(userId);
        tokenService.revoke(userId, tokenId, actorUserId);
    }

    private User account(String userId) {
        tokenService.requireEnabled();
        return userRepository.findById(userId).filter(User::isServiceAccount)
                .orElseThrow(() -> new ResourceNotFoundException("Service account", userId));
    }

    private ServiceAccountView view(User u, Timestamp now) {
        User owner = u.getServiceOwnerUserId() == null ? null : userRepository.findById(u.getServiceOwnerUserId()).orElse(null);
        String name = u.getEmail().endsWith(EMAIL_DOMAIN) ? u.getEmail().substring(0, u.getEmail().length() - EMAIL_DOMAIN.length()) : u.getEmail();
        return new ServiceAccountView(u.getUserId(), name, u.getEmail(), u.isEnvAdmin() ? "ENV_ADMIN" : "USER",
                u.getServiceOwnerUserId(), owner == null ? null : owner.getDisplayName(), u.isActive(), u.getCreatedAt(),
                tokenRepository.countActive(u.getUserId(), now));
    }
}
