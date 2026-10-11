package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.TeamDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teams (E23-T01): unique slugs, the deactivate guard, the V57 backfill grouping ownerTeam
 * variants, the resolver's precedence, and team spend reported under the team's name.
 */
class TeamServiceTest extends AbstractIntegrationTest {

    @Autowired private TeamService teams;
    @Autowired private TeamRepository teamRepository;
    @Autowired private EnvironmentRepository environments;
    @Autowired private TeamResolver resolver;
    @Autowired private CostEstimationService costEstimationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Team team(String displayName) {
        return teamRepository.findById(teams.create("admin-001", new TeamDTO.Request(null, displayName, "CC-1")).teamId()).orElseThrow();
    }

    private void owns(Environment env, Team team) {
        env.setTeam(team);
        environments.saveAndFlush(env);
    }

    @Test
    void namesAreUniqueSlugs() {
        TeamDTO created = teams.create("admin-001", new TeamDTO.Request(null, " Platform Team ", null));
        assertThat(created.name()).isEqualTo("platform-team");
        assertThat(created.displayName()).isEqualTo("Platform Team");
        assertThatThrownBy(() -> teams.create("admin-001", new TeamDTO.Request("PLATFORM team", "Other", null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A team named 'platform-team' already exists");
        assertThat(TeamService.slug("--Data & AI!--")).isEqualTo("data-ai");
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'TEAM_CREATED' AND target_id = ?", Integer.class,
                created.teamId())).isEqualTo(1));
    }

    @Test
    void aTeamWithAnActiveEnvironmentCannotBeDeactivated() {
        Team platform = team("Platform");
        Environment env = newEnvironment("Owned");
        owns(env, platform);

        assertThatThrownBy(() -> teams.deactivate(platform.getTeamId(), "admin-001"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("still owns 1 active environment");
        assertThat(teams.get(platform.getTeamId()).environmentCount()).isEqualTo(1);

        jdbcTemplate.update("UPDATE environment SET is_active = FALSE WHERE environment_id = ?", env.getEnvironmentId());
        assertThat(teams.deactivate(platform.getTeamId(), "admin-001").active()).isFalse();
        assertThat(teams.list(false)).extracting(TeamDTO::teamId).doesNotContain(platform.getTeamId());
    }

    @Test
    void theBackfillGroupsOwnerTeamVariantsIntoOneTeam() throws Exception {
        Environment a = newEnvironment("Backfill A");
        Environment b = newEnvironment("Backfill B");
        Environment c = newEnvironment("Backfill C");
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id = ?", "{\"ownerTeam\":\"Platform Team\"}", a.getEnvironmentId());
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id = ?", "{\"ownerTeam\":\"Platform Team \"}", b.getEnvironmentId());
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id = ?", "not json", c.getEnvironmentId());

        String sql = new String(new ClassPathResource("db/migration/V57__add_team.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        Arrays.stream(sql.split(";")).map(String::trim)
                .filter(s -> s.startsWith("INSERT INTO team") || s.startsWith("UPDATE environment"))
                .forEach(jdbcTemplate::update);

        Team platform = teamRepository.findByName("platform-team").orElseThrow();
        assertThat(platform.getDisplayName()).isEqualTo("Platform Team");
        assertThat(environments.findById(a.getEnvironmentId()).orElseThrow().getTeamId()).isEqualTo(platform.getTeamId());
        assertThat(environments.findById(b.getEnvironmentId()).orElseThrow().getTeamId()).isEqualTo(platform.getTeamId());
        assertThat(environments.findById(c.getEnvironmentId()).orElseThrow().getTeamId()).isNull();
    }

    @Test
    void theResolverPrefersTheTeamThenMetadataThenUnassigned() {
        Environment env = newEnvironment("Resolve");
        assertThat(resolver.resolveTeam(env)).isEqualTo("Unassigned");
        env.setMetadata("{\"ownerTeam\":\"Legacy Name\"}");
        assertThat(resolver.resolveTeam(env)).isEqualTo("Legacy Name");
        env.setTeam(team("Payments"));
        assertThat(resolver.resolveTeam(env)).isEqualTo("Payments");
    }

    @Test
    void teamSpendIsReportedUnderTheTeamsName() {
        Environment env = newEnvironment("Spend");
        jdbcTemplate.update("UPDATE environment SET metadata = ? WHERE environment_id = ?", "{\"ownerTeam\":\"old free text\"}",
                env.getEnvironmentId());
        owns(environments.findById(env.getEnvironmentId()).orElseThrow(), team("Search"));
        jdbcTemplate.update("INSERT INTO cost_daily_snapshot (snapshot_id, environment_id, snapshot_date, estimated_cost) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), env.getEnvironmentId(), java.sql.Date.valueOf(LocalDate.now().minusDays(1)), new BigDecimal("12.00"));

        assertThat(costEstimationService.getSpendTrendByTeam(7)).extracting(p -> p.team()).contains("Search").doesNotContain("old free text");
    }
}
