package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Required allocation fields (E23-T04): the flag matrix. */
class AllocationValidatorTest {

    private AllocationValidator validator(boolean requireTeam, String keys) {
        AllocationValidator v = new AllocationValidator(new ObjectMapper());
        ReflectionTestUtils.setField(v, "requireTeam", requireTeam);
        ReflectionTestUtils.setField(v, "requiredMetadataKeys", keys);
        return v;
    }

    private static Environment env(Team team, String metadata) {
        Environment e = new Environment();
        e.setTeam(team);
        e.setMetadata(metadata);
        return e;
    }

    @Test
    void bothOffChangesNothing() {
        assertThat(validator(false, "").missing(env(null, null))).isEmpty();
    }

    @Test
    void aMissingTeamAndMetadataKeysAreListed() {
        AllocationValidator v = validator(true, "costCenter, project");
        assertThat(v.missing(env(null, "{\"project\":\"  \"}"))).containsExactly("team", "costCenter", "project");
        assertThat(v.missing(env(new Team(), "{\"costCenter\":\"CC-1\",\"project\":\"x\"}"))).isEmpty();
        assertThat(v.missing(env(new Team(), "not json"))).containsExactly("costCenter", "project");
        assertThatThrownBy(() -> v.validate(env(null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Missing allocation fields: team, costCenter, project");
        assertThat(v.rules().requiredMetadataKeys()).containsExactly("costCenter", "project");
    }
}
