package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.exception.ConflictException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for secrets at rest such as chat webhook URLs (E24-T02). Each value gets a random
 * 12-byte IV and is stored as base64(iv | ciphertext+tag), so tampering fails decryption. The key
 * is chat.secret-key (base64, 32 bytes); startup fails when chat.enabled is on without one.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${chat.secret-key:}")
    private String encodedKey = "";

    @Value("${chat.enabled:false}")
    private boolean chatEnabled;

    private SecretKeySpec key;

    public SecretCipher() {
    }

    /** For tests. */
    SecretCipher(String encodedKey) {
        this.encodedKey = encodedKey;
        init();
    }

    @PostConstruct
    void init() {
        key = parse(encodedKey);
        if (key == null && chatEnabled) {
            throw new IllegalStateException("chat.enabled=true needs chat.secret-key (CHAT_SECRET_KEY): base64 of 32 random bytes");
        }
    }

    private static SecretKeySpec parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("chat.secret-key is not valid base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("chat.secret-key must decode to 32 bytes (it has " + bytes.length + ")");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new ConflictException("Chat secrets cannot be stored: set CHAT_SECRET_KEY (base64 of 32 random bytes)");
        }
        return key;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the secret");
        }
    }

    /** Throws IllegalStateException when the value was tampered with or the key changed. */
    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("Stored secret is too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored secret could not be decrypted (tampered, or the key changed)");
        }
    }
}
