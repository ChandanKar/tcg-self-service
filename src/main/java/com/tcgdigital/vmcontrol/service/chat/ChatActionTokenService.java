package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ChatActionToken;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import com.tcgdigital.vmcontrol.repository.ChatActionTokenRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Signed one-click links for chat buttons (E24-T04): token = base64url(payload) "." base64url(
 * HMAC-SHA256(payload)) with payload {jti, type, envId, targetId, exp}. The jti is stored, so a
 * link works once ({@link #markUsed}). A token grants nothing on its own: the action runs as the
 * signed-in user with their normal permission checks.
 */
@Service
public class ChatActionTokenService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ChatActionTokenRepository tokenRepository;
    private final ObjectMapper objectMapper;

    @Value("${chat.action-signing-key:}")
    private String signingKey = "";

    @Value("${chat.action-ttl-hours:24}")
    private long ttlHours = 24;

    @Value("${chat.enabled:false}")
    private boolean chatEnabled;

    public ChatActionTokenService(ChatActionTokenRepository tokenRepository, ObjectMapper objectMapper) {
        this.tokenRepository = tokenRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void checkKey() {
        if (chatEnabled && !isConfigured()) {
            throw new IllegalStateException("chat.enabled=true needs chat.action-signing-key (CHAT_ACTION_SIGNING_KEY), at least 32 characters");
        }
    }

    public boolean isConfigured() {
        return signingKey != null && signingKey.trim().length() >= 32;
    }

    /** The decoded, verified claims of a link. */
    public record Claims(String jti, ChatActionType type, String environmentId, String targetId, Instant expiresAt) {}

    @Transactional
    public String issue(ChatActionType type, String environmentId, String targetId) {
        return issue(type, environmentId, targetId, Duration.ofHours(ttlHours));
    }

    @Transactional
    public String issue(ChatActionType type, String environmentId, String targetId, Duration ttl) {
        if (!isConfigured()) {
            throw new IllegalStateException("chat.action-signing-key is not set");
        }
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        tokenRepository.save(new ChatActionToken(jti, type, environmentId, targetId, Timestamp.from(now), Timestamp.from(exp)));
        ObjectNode payload = objectMapper.createObjectNode()
                .put("jti", jti).put("type", type.name()).put("envId", environmentId).put("targetId", targetId)
                .put("exp", exp.getEpochSecond());
        String body = B64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return body + "." + B64.encodeToString(sign(body));
    }

    /**
     * Checks the signature and expiry, and that the link exists and is unused. 400 when invalid or
     * expired, 409 when already used.
     */
    @Transactional(readOnly = true)
    public Claims verify(String token) {
        Claims claims = parse(token);
        if (Instant.now().isAfter(claims.expiresAt())) {
            throw new ValidationException("This link has expired");
        }
        ChatActionToken stored = tokenRepository.findById(claims.jti())
                .orElseThrow(() -> new ValidationException("This link is not valid"));
        if (stored.getActionType() != claims.type()) {
            throw new ValidationException("This link is not valid");
        }
        if (stored.getUsedAt() != null) {
            throw new ConflictException("This link was already used");
        }
        return claims;
    }

    /** Uses the link: exactly one caller wins, any other gets 409. Call inside the action's transaction. */
    public void markUsed(String jti, String userId) {
        if (tokenRepository.markUsed(jti, userId, new Timestamp(System.currentTimeMillis())) != 1) {
            throw new ConflictException("This link was already used");
        }
    }

    Claims parse(String token) {
        if (!isConfigured()) {
            throw new ValidationException("Chat actions are not configured");
        }
        if (token == null) {
            throw new ValidationException("This link is not valid");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            throw new ValidationException("This link is not valid");
        }
        String body = token.substring(0, dot);
        byte[] signature;
        try {
            signature = B64D.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("This link is not valid");
        }
        if (!MessageDigest.isEqual(sign(body), signature)) {
            throw new ValidationException("This link is not valid");
        }
        try {
            JsonNode p = objectMapper.readTree(B64D.decode(body));
            return new Claims(p.get("jti").asText(), ChatActionType.valueOf(p.get("type").asText()),
                    text(p, "envId"), text(p, "targetId"), Instant.ofEpochSecond(p.get("exp").asLong()));
        } catch (Exception e) {
            throw new ValidationException("This link is not valid");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign the chat action link");
        }
    }
}
