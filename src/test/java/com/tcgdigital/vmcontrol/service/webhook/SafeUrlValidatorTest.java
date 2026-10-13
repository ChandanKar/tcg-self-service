package com.tcgdigital.vmcontrol.service.webhook;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Webhook URLs never reach internal networks (E34-T06). */
class SafeUrlValidatorTest {

    private static InetAddress ip(String text) {
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A resolver answering from a fixed table, so no test needs DNS. */
    private static SafeUrlValidator validator(Map<String, String[]> table) {
        return new SafeUrlValidator(host -> {
            String[] addresses = table.get(host);
            if (addresses == null) throw new UnknownHostException(host);
            InetAddress[] result = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) result[i] = ip(addresses[i]);
            return result;
        });
    }

    @Test
    void publicHttpsHostsPass() {
        SafeUrlValidator v = validator(Map.of("hooks.example.com", new String[]{"93.184.216.34", "2606:2800:220:1::248"}));

        assertThat(v.validate("https://hooks.example.com/in?x=1").getHost()).isEqualTo("hooks.example.com");
    }

    @Test
    void privateLoopbackLinkLocalAndMetadataAddressesAreRefused() {
        for (String address : new String[]{"169.254.169.254", "10.0.0.5", "127.0.0.1", "172.20.1.1", "192.168.1.10",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1"}) {
            SafeUrlValidator v = validator(Map.of("evil.example.com", new String[]{address}));
            assertThatThrownBy(() -> v.validate("https://evil.example.com/hook")).as(address)
                    .isInstanceOf(ValidationException.class).hasMessageContaining("private or reserved");
        }
    }

    @Test
    void oneInternalRecordAmongPublicOnesIsEnough() {
        SafeUrlValidator v = validator(Map.of("mixed.example.com", new String[]{"93.184.216.34", "10.1.2.3"}));

        assertThatThrownBy(() -> v.validate("https://mixed.example.com/")).isInstanceOf(ValidationException.class);
    }

    @Test
    void onlyHttpsWithoutCredentialsToAResolvingNonInternalName() {
        SafeUrlValidator v = validator(Map.of("hooks.example.com", new String[]{"93.184.216.34"}));

        assertThatThrownBy(() -> v.validate("http://hooks.example.com/")).hasMessageContaining("https");
        assertThatThrownBy(() -> v.validate("https://user:pw@hooks.example.com/")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> v.validate("https://localhost/")).hasMessageContaining("internal");
        assertThatThrownBy(() -> v.validate("https://metadata.google.internal/")).hasMessageContaining("internal");
        assertThatThrownBy(() -> v.validate("https://nowhere.example.com/")).hasMessageContaining("does not resolve");
        assertThatThrownBy(() -> v.validate("not a url")).isInstanceOf(ValidationException.class);
    }

    @Test
    void ipLiteralsAreCheckedToo() {
        SafeUrlValidator real = new SafeUrlValidator();

        assertThatThrownBy(() -> real.validate("https://169.254.169.254/latest/meta-data")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> real.validate("https://[::1]/")).isInstanceOf(ValidationException.class);
        assertThat(real.validate("https://93.184.216.34/hook").getHost()).isEqualTo("93.184.216.34");
    }
}
