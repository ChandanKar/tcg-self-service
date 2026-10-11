package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CommitmentRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

public interface CommitmentRecommendationRepository extends JpaRepository<CommitmentRecommendation, String> {

    Optional<CommitmentRecommendation> findByGeneratedOnAndRecTypeAndTermAndPaymentOption(
            Date generatedOn, CommitmentRecommendation.Type recType, String term, String paymentOption);

    Optional<CommitmentRecommendation> findTopByRecTypeOrderByGeneratedOnDesc(CommitmentRecommendation.Type recType);

    /** The rows of the most recent capture (E27-T01): one per type, term and payment option. */
    @Query("SELECT r FROM CommitmentRecommendation r WHERE r.generatedOn = "
            + "(SELECT MAX(x.generatedOn) FROM CommitmentRecommendation x) ORDER BY r.recType")
    List<CommitmentRecommendation> findLatest();
}
