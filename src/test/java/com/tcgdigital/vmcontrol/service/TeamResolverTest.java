package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeamResolverTest {

    private final TeamResolver resolver = new TeamResolver(new ObjectMapper());

    @Test
    void resolvesOwnerTeamFromMetadataJson() {
        assertEquals("payments", resolver.resolveTeam("{\"ownerTeam\":\"payments\",\"region\":\"ap-south-1\"}"));
    }

    @Test
    void returnsUnassignedWhenMetadataIsNull() {
        assertEquals("Unassigned", resolver.resolveTeam(null));
    }

    @Test
    void returnsUnassignedWhenMetadataIsBlank() {
        assertEquals("Unassigned", resolver.resolveTeam("   "));
    }

    @Test
    void returnsUnassignedWhenOwnerTeamKeyMissing() {
        assertEquals("Unassigned", resolver.resolveTeam("{\"region\":\"ap-south-1\"}"));
    }

    @Test
    void returnsUnassignedWhenOwnerTeamIsBlank() {
        assertEquals("Unassigned", resolver.resolveTeam("{\"ownerTeam\":\"   \"}"));
    }

    @Test
    void returnsUnassignedWhenMetadataIsNotValidJson() {
        assertEquals("Unassigned", resolver.resolveTeam("not-json-at-all"));
    }
}
