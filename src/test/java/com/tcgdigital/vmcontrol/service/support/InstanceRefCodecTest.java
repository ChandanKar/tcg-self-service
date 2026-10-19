package com.tcgdigital.vmcontrol.service.support;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** U01-T02: a reference names one instance for one user for a short time, and nobody can read or alter it. */
class InstanceRefCodecTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private static InstanceRefCodec codecAt(Instant now) {
        return new InstanceRefCodec(KEY, 30, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aReferenceResolvesToTheInstanceForTheUserItWasIssuedTo() {
        String ref = codecAt(NOW).encode(CloudProvider.AWS, "acct-b", "ap-south-1", "i-0123456789abcdef0", "u-1");

        InstanceRefCodec.InstanceRef resolved = codecAt(NOW.plus(Duration.ofMinutes(29))).decode(ref, "u-1");

        assertThat(resolved).isEqualTo(new InstanceRefCodec.InstanceRef(CloudProvider.AWS, "acct-b", "ap-south-1", "i-0123456789abcdef0"));
        assertThat(ref).doesNotContain("i-0123").doesNotContain("ap-south");
    }

    @Test
    void anotherUserCannotUseIt() {
        String ref = codecAt(NOW).encode(CloudProvider.AWS, null, "ap-south-1", "i-0123456789abcdef0", "u-1");

        assertThatThrownBy(() -> codecAt(NOW).decode(ref, "u-2"))
                .isInstanceOf(ValidationException.class).hasMessage(InstanceRefCodec.OUT_OF_DATE);
    }

    @Test
    void itExpires() {
        String ref = codecAt(NOW).encode(CloudProvider.AWS, null, "ap-south-1", "i-0123456789abcdef0", "u-1");

        assertThatThrownBy(() -> codecAt(NOW.plus(Duration.ofMinutes(31))).decode(ref, "u-1"))
                .isInstanceOf(ValidationException.class).hasMessage(InstanceRefCodec.OUT_OF_DATE);
    }

    @Test
    void anAlteredOrMadeUpReferenceIsRefused() {
        String ref = codecAt(NOW).encode(CloudProvider.AWS, null, "ap-south-1", "i-0123456789abcdef0", "u-1");
        // Change a character in the middle: every bit of it is used. The last character can carry
        // unused padding bits, so changing it (A to B) sometimes left the bytes, and the reference, the same.
        int at = ref.length() / 2;
        String altered = ref.substring(0, at) + (ref.charAt(at) == 'A' ? 'B' : 'A') + ref.substring(at + 1);

        for (String bad : new String[]{altered, "not-a-ref", "", "AAAA"}) {
            assertThatThrownBy(() -> codecAt(NOW).decode(bad, "u-1"))
                    .isInstanceOf(ValidationException.class).hasMessage(InstanceRefCodec.OUT_OF_DATE);
        }
    }

    @Test
    void aReferenceFromAnotherKeyIsRefused() {
        String other = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        String ref = new InstanceRefCodec(other, 30, Clock.fixed(NOW, ZoneOffset.UTC))
                .encode(CloudProvider.AWS, null, "ap-south-1", "i-0123456789abcdef0", "u-1");

        assertThatThrownBy(() -> codecAt(NOW).decode(ref, "u-1")).isInstanceOf(ValidationException.class);
    }

    @Test
    void withoutAKeyItStillWorksWithinOneProcess() {
        InstanceRefCodec codec = new InstanceRefCodec("", 30, Clock.fixed(NOW, ZoneOffset.UTC));

        String ref = codec.encode(CloudProvider.AWS, null, "ap-south-1", "i-0123456789abcdef0", "u-1");

        assertThat(codec.decode(ref, "u-1").instanceId()).isEqualTo("i-0123456789abcdef0");
    }
}
