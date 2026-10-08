package com.tcgdigital.vmcontrol.service.support;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edit metadata is a patch over the stored metadata (E10-T03, M4).
 */
class JsonMetadataMergerTest {

    @Test
    void patchKeysOverrideAndOtherKeysAreKept() {
        String merged = JsonMetadataMerger.merge("{\"region\":\"eu-west-1\",\"ownerTeam\":\"old\"}",
                "{\"ownerTeam\":\"payments\",\"defaultCloudProvider\":\"AWS\"}");

        assertThat(merged).contains("\"region\":\"eu-west-1\"", "\"ownerTeam\":\"payments\"", "\"defaultCloudProvider\":\"AWS\"");
    }

    @Test
    void aNullValueRemovesTheKey() {
        assertThat(JsonMetadataMerger.merge("{\"region\":\"eu-west-1\",\"ownerTeam\":\"old\"}", "{\"ownerTeam\":null}"))
                .isEqualTo("{\"region\":\"eu-west-1\"}");
        assertThat(JsonMetadataMerger.merge("{\"ownerTeam\":\"old\"}", "{\"ownerTeam\":null}")).isNull();
    }

    @Test
    void noStoredMetadataOrNoPatch() {
        assertThat(JsonMetadataMerger.merge(null, "{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(JsonMetadataMerger.merge("{\"a\":1}", null)).isEqualTo("{\"a\":1}");
        assertThat(JsonMetadataMerger.merge("not json", "{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void aPatchThatIsNotAJsonObjectIsRejected() {
        assertThatThrownBy(() -> JsonMetadataMerger.merge("{}", "not json")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> JsonMetadataMerger.merge("{}", "[1,2]")).isInstanceOf(ValidationException.class);
    }
}
