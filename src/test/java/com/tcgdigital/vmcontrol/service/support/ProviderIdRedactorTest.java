package com.tcgdigital.vmcontrol.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** U01-T03: AWS resource ids in free text are masked, everything else is left as it was. */
class ProviderIdRedactorTest {

    @Test
    void instanceAndOtherResourceIdsAreMaskedKeepingTheirKind() {
        assertThat(ProviderIdRedactor.redact("The instance i-0123456789abcdef0 is not in a state from which it can be started."))
                .isEqualTo("The instance i-… is not in a state from which it can be started.");
        assertThat(ProviderIdRedactor.redact("old instance i-0abc1234 replaced"))
                .isEqualTo("old instance i-… replaced");
        assertThat(ProviderIdRedactor.redact("volume vol-0fedcba9876543210, snapshot snap-0123456789abcdef0, ip eipalloc-0123abcd"))
                .isEqualTo("volume vol-…, snapshot snap-…, ip eipalloc-…");
        assertThat(ProviderIdRedactor.redact("spot request sir-08b93456 in subnet-0aaaaaaaaaaaaaaa1 of vpc-0123abcd"))
                .isEqualTo("spot request sir-… in subnet-… of vpc-…");
    }

    @Test
    void arnsAreMasked() {
        assertThat(ProviderIdRedactor.redact("User: arn:aws:iam::123456789012:user/ci is not authorized"))
                .isEqualTo("User: arn:… is not authorized");
    }

    @Test
    void textWithoutIdsIsUnchanged() {
        for (String text : new String[]{"Insufficient capacity.", "pay-api-02 didn't start", "web-01 is mine",
                "i-am-not-an-id", "account 123456789012", "", "db:orders and cluster:aurora-qa"}) {
            assertThat(ProviderIdRedactor.redact(text)).isEqualTo(text);
        }
        assertThat(ProviderIdRedactor.redact(null)).isNull();
    }

    @Test
    void nestedPayloadsAreMaskedEverywhere() {
        Object out = ProviderIdRedactor.redactDeep(Map.of(
                "vm", Map.of("providerVmId", "i-0123456789abcdef0", "name", "web-01"),
                "steps", List.of("started i-0123456789abcdef0", 3)));

        assertThat(out.toString()).doesNotContain("i-0123456789abcdef0").contains("web-01").contains("3");
    }
}
