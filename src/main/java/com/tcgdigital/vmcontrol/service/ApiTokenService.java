package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.ApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreatedApiTokenDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ApiScope;
import com.tcgdigital.vmcontrol.model.ApiToken;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ApiTokenRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Personal API tokens (E34-T01). A token reads {@code vmc_<prefix8>_<secret43>}: the prefix finds
 * its row, and only HMAC-SHA256(api-tokens.pepper, token) is stored, so the database alone cannot
 * produce a working token. The token is shown once, at creation. Every token expires, holds only
 * scopes its owner's role allows, and is checked in constant time. Off (api-tokens.enabled, read at
 * startup), the API answers 404 and no token verifies.
 */
@Service
public class ApiTokenService {

    public static final String TOKEN_START = "vmc_";
    static final int MAX_ACTIVE_PER_USER = 20;
    private static final Pattern FORMAT = Pattern.compile("^vmc_([A-Za-z0-9]{8})_([A-Za-z0-9_-]{43})$");
    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${api-tokens.enabled:false}")
    private boolean enabled;

    @Value("${api-tokens.max-lifetime-days:365}")
    private int maxLifetimeDays;

    @Value("${api-tokens.pepper:}")
    private String pepper;

    private final ApiTokenRepository repository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private Clock clock = Clock.systemUTC();

    public ApiTokenService(ApiTokenRepository repository, UserRepository userRepository, AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Tokens on without a strong pepper would store guessable hashes: refuse to start. */
    @PostConstruct
    void checkPepper() {
        if (enabled && !pepperUsable()) {
            throw new IllegalStateException("api-tokens.enabled=true needs api-tokens.pepper (API_TOKEN_PEPPER), at least 32 bytes");
        }
    }

    private boolean pepperUsable() {
        return pepper != null && pepper.trim().getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public boolean isEnabled() {
        return enabled && pepperUsable();
    }

    /** 404 while tokens are off: the feature is invisible. */
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new ResourceNotFoundException("API tokens are not enabled");
        }
    }

    public List<ApiTokenDTO> list(String userId) {
        requireEnabled();
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(ApiTokenDTO::from).toList();
    }

    /** Creates a token for the user and returns it, the only time it is ever shown. */
    public CreatedApiTokenDTO create(User user, CreateApiTokenDTO dto) {
        return create(user, dto, user.getUserId());
    }

    /** Creates a token for {@code user}, asked for by {@code actorUserId} (an admin for a service account, E34-T03). */
    public CreatedApiTokenDTO create(User user, CreateApiTokenDTO dto, String actorUserId) {
        requireEnabled();
        String name = dto == null || dto.name() == null ? "" : dto.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new ValidationException("Token name must be 1 to 100 characters");
        }
        Set<String> scopes = scopes(user, dto.scopes());
        Integer days = dto.expiresInDays();
        if (days == null || days < 1 || days > maxLifetimeDays) {
            throw new ValidationException("expiresInDays must be between 1 and " + maxLifetimeDays);
        }
        Instant now = clock.instant();
        if (repository.countActive(user.getUserId(), Timestamp.from(now)) >= MAX_ACTIVE_PER_USER) {
            throw new ValidationException("At most " + MAX_ACTIVE_PER_USER + " active tokens; revoke one first");
        }

        String prefix = newPrefix();
        String token = TOKEN_START + prefix + "_" + secret();
        ApiToken t = new ApiToken();
        t.setTokenId(UUID.randomUUID().toString());
        t.setUserId(user.getUserId());
        t.setName(name);
        t.setTokenPrefix(prefix);
        t.setTokenHash(hash(token));
        t.setScopes(String.join(",", scopes));
        t.setExpiresAt(Timestamp.from(now.plus(Duration.ofDays(days))));
        t.setCreatedAt(Timestamp.from(now));
        repository.saveAndFlush(t);
        auditService.logAction(actorUserId, AuditAction.API_TOKEN_CREATED, "API_TOKEN", t.getTokenId(), name,
                "Prefix " + prefix + ", scopes " + t.getScopes() + ", expires " + t.getExpiresAt().toInstant()
                        + (actorUserId.equals(user.getUserId()) ? "" : ", for " + user.getEmail()));
        return CreatedApiTokenDTO.of(token, ApiTokenDTO.from(t));
    }

    /** Revokes one of the user's tokens; another user's token is not found. */
    public void revoke(String userId, String tokenId, String actorUserId) {
        requireEnabled();
        ApiToken t = repository.findById(tokenId).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("API token", tokenId));
        if (repository.revoke(tokenId, userId, actorUserId, Timestamp.from(clock.instant())) == 1) {
            auditService.logAction(actorUserId, AuditAction.API_TOKEN_REVOKED, "API_TOKEN", tokenId, t.getName(),
                    "Prefix " + t.getTokenPrefix());
        }
    }

    /** A verified token and its active owner. */
    public record Verified(ApiToken token, User user) {
    }

    /**
     * The token and its owner when the raw token is well formed, known, matches its hash, is not
     * revoked or expired and its owner is active; empty otherwise (and always while tokens are off).
     */
    public Optional<Verified> verify(String raw) {
        if (!isEnabled() || raw == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<ApiToken> found = repository.findByTokenPrefix(m.group(1));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiToken t = found.get();
        byte[] expected = t.getTokenHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(raw.trim()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (t.getRevokedAt() != null || !t.getExpiresAt().toInstant().isAfter(now)) {
            return Optional.empty();
        }
        return userRepository.findById(t.getUserId()).filter(User::isActive).map(u -> new Verified(t, u));
    }

    private static Set<String> scopes(User user, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ValidationException("Choose at least one scope");
        }
        Set<ApiScope> allowed = ApiScope.allowedFor(user);
        Set<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            ApiScope scope = ApiScope.fromValue(value == null ? "" : value.trim())
                    .orElseThrow(() -> new ValidationException("Unknown scope: " + value));
            if (!allowed.contains(scope)) {
                throw new ValidationException("Your role cannot grant the scope " + scope.value());
            }
            result.add(scope.value());
        }
        return result;
    }

    private String newPrefix() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
            }
            if (!repository.existsByTokenPrefix(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Could not draw an unused token prefix");
    }

    /** 32 random bytes, URL-safe base64 without padding: 43 characters. */
    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** HMAC-SHA256 of the whole token with the pepper, as 64 hex characters. */
    String hash(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
