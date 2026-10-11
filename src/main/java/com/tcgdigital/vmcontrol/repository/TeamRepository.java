package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, String> {

    Optional<Team> findByName(String name);

    Optional<Team> findByEntraGroupId(String entraGroupId);

    List<Team> findByIsActiveTrueOrderByDisplayName();

    List<Team> findAllByOrderByDisplayName();

    /** Active environments per team: [teamId, count]. */
    @Query("SELECT e.team.teamId, COUNT(e) FROM Environment e WHERE e.team IS NOT NULL AND e.isActive = true GROUP BY e.team.teamId")
    List<Object[]> countActiveEnvironments();
}
