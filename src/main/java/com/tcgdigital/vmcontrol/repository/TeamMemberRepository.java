package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMember.Key> {

    List<TeamMember> findByTeamId(String teamId);

    @Query("SELECT m.teamId FROM TeamMember m WHERE m.userId = :userId")
    List<String> findTeamIdsByUserId(@Param("userId") String userId);

    /** Members per team: [teamId, count]. */
    @Query("SELECT m.teamId, COUNT(m) FROM TeamMember m GROUP BY m.teamId")
    List<Object[]> countByTeam();
}
