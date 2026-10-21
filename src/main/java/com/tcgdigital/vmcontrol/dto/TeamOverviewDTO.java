package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.service.QuotaService;

import java.sql.Timestamp;
import java.util.List;

/**
 * A team as the Teams page and Team detail show it (U13-T06, Teams.dc.html, TeamDetail.dc.html):
 * its active environments, its quotas with this month's use (empty while quotas are off), and its
 * Entra group with how many members came from it and when they were last synced.
 */
public record TeamOverviewDTO(
        String teamId,
        String name,
        String displayName,
        String costCenter,
        boolean active,
        long memberCount,
        String entraGroupId,
        String entraGroupName,
        long entraMemberCount,
        Timestamp lastSyncedAt,
        List<Environment> environments,
        List<QuotaService.QuotaView> quotas
) {
    public record Environment(String environmentId, String name, String displayName, boolean production) {
    }

    /** PUT /teams/{id}/environments: the environments that belong to the team. */
    public record EnvironmentsRequest(List<String> environmentIds) {
    }
}
