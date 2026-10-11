package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-GCM secrets at rest (E24-T02): round trip, random IVs, tamper detection, key checks. */
class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String URL = "https://prod-1.westeurope.logic.azure.com/workflows/x?sig=abc";

    @Test
    void roundTripWithAFreshIvEachTime() {
        SecretCipher cipher = new SecretCipher(KEY);
        String a = cipher.encrypt(URL);
        String b = cipher.encrypt(URL);
        assertThat(a).isNotEqualTo(b).doesNotContain("logic.azure.com");
        assertThat(cipher.decrypt(a)).isEqualTo(URL);
        assertThat(cipher.decrypt(b)).isEqualTo(URL);
    }

    @Test
    void tamperingOrAnotherKeyFails() {
        SecretCipher cipher = new SecretCipher(KEY);
        byte[] sealed = Base64.getDecoder().decode(cipher.encrypt(URL));
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(sealed)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("AAAA")).isInstanceOf(IllegalStateException.class);

        byte[] otherKey = new byte[32];
        otherKey[0] = 7;
        SecretCipher other = new SecretCipher(Base64.getEncoder().encodeToString(otherKey));
        assertThatThrownBy(() -> other.decrypt(cipher.encrypt(URL))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyRules() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SecretCipher("not base64!")).isInstanceOf(IllegalStateException.class);

        SecretCipher none = new SecretCipher("");
        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(() -> none.encrypt(URL)).isInstanceOf(ConflictException.class);

        SecretCipher enabledWithoutKey = new SecretCipher("");
        ReflectionTestUtils.setField(enabledWithoutKey, "chatEnabled", true);
        assertThatThrownBy(enabledWithoutKey::init).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.secret-key");
    }
}
