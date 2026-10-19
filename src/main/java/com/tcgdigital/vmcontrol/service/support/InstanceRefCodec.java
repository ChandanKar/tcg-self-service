package com.tcgdigital.vmcontrol.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Opaque references to cloud instances for people who may not see instance ids (U01-T02). An environment
 * admin picks an EC2 instance from a list by name; the list carries a reference instead of the id, and
 * registering sends the reference back.
 *
 * <p>A reference is AES-256-GCM over the provider, account, region, instance id, the user it was issued to
 * and an expiry, so it cannot be read, altered, reused by someone else or kept for later. It is stateless,
 * so any app node can resolve it as long as every node has the same key: vm.instance-ref.key
 * (INSTANCE_REF_KEY, base64 of 32 bytes). Without a key each process makes its own, and references then
 * only work on the node that issued them.
 */
@Component
public class InstanceRefCodec {

    private static final Logger log = LoggerFactory.getLogger(InstanceRefCodec.class);

    public static final String OUT_OF_DATE = "This list is out of date. Fetch the instances again.";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a reference stands for. */
    public record InstanceRef(CloudProvider provider, String accountRef, String region, String instanceId) {
    }

    private record Payload(String p, String a, String r, String i, String u, long e) {
    }

    @Value("${vm.instance-ref.key:}")
    private String encodedKey = "";

    @Value("${vm.instance-ref.ttl-minutes:30}")
    private long ttlMinutes = 30;

    private Clock clock = Clock.systemUTC();
    private SecretKeySpec key;

    public InstanceRefCodec() {
    }

    /** For tests. */
    InstanceRefCodec(String encodedKey, long ttlMinutes, Clock clock) {
        this.encodedKey = encodedKey;
        this.ttlMinutes = ttlMinutes;
        this.clock = clock;
        init();
    }

    @PostConstruct
    void init() {
        if (encodedKey == null || encodedKey.isBlank()) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            key = new SecretKeySpec(random, "AES");
            log.warn("vm.instance-ref.key (INSTANCE_REF_KEY) is not set: instance references only work on this node "
                    + "and until it restarts. Set the same base64 32-byte key on every node.");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("vm.instance-ref.key is not valid base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("vm.instance-ref.key must decode to 32 bytes (it has " + bytes.length + ")");
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    /** A reference to {@code instanceId}, usable by {@code userId} until the TTL runs out. */
    public String encode(CloudProvider provider, String accountRef, String region, String instanceId, String userId) {
        Objects.requireNonNull(instanceId, "instanceId");
        long expires = Instant.now(clock).plus(Duration.ofMinutes(ttlMinutes)).getEpochSecond();
        try {
            byte[] plain = JSON.writeValueAsBytes(new Payload(provider.name(), accountRef, region, instanceId, userId, expires));
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain);
            byte[] all = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Could not create an instance reference", e);
        }
    }

    /**
     * The instance behind {@code ref}, when it was issued to {@code userId} and has not expired; otherwise a
     * ValidationException asking for a fresh list.
     */
    public InstanceRef decode(String ref, String userId) {
        Payload payload;
        try {
            byte[] all = Base64.getUrlDecoder().decode(ref);
            if (all.length <= IV_BYTES) {
                throw new ValidationException(OUT_OF_DATE);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            payload = JSON.readValue(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), Payload.class);
        } catch (GeneralSecurityException | IllegalArgumentException | java.io.IOException e) {
            throw new ValidationException(OUT_OF_DATE);
        }
        if (!Objects.equals(payload.u(), userId) || Instant.now(clock).getEpochSecond() > payload.e()) {
            throw new ValidationException(OUT_OF_DATE);
        }
        return new InstanceRef(CloudProvider.valueOf(payload.p()), payload.a(), payload.r(), payload.i());
    }
}
