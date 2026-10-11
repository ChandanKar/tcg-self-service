package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CommitmentRecommendation;
import com.tcgdigital.vmcontrol.repository.CommitmentRecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.util.*;

/**
 * AWS purchase recommendations for Savings Plans (Compute and EC2 Instance) and EC2 Reserved
 * Instances (E27-T01), captured weekly through the shared Cost Explorer client for display next
 * to schedule data. Each of the three calls is captured on its own, so a denied permission on one
 * does not blank the others; a rerun on the same day updates that day's rows. Read-only: no
 * purchase API is called. Three billed Cost Explorer calls (plus extra pages) per capture.
 */
@Service
public class CommitmentRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(CommitmentRecommendationService.class);
    static final String EC2_SERVICE = "Amazon Elastic Compute Cloud - Compute";
    private static final int TOP_DETAILS = 10;

    private final CommitmentRecommendationRepository repository;
    private final CostExplorerClientProvider clientProvider;
    private final CostDayBoundary dayBoundary;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cost.commitments.recommendations.enabled:true}")
    private boolean enabled = true;

    @Value("${cost.commitments.term:ONE_YEAR}")
    private String term = "ONE_YEAR";

    @Value("${cost.commitments.payment-option:NO_UPFRONT}")
    private String paymentOption = "NO_UPFRONT";

    @Value("${cost.commitments.lookback:THIRTY_DAYS}")
    private String lookback = "THIRTY_DAYS";

    public CommitmentRecommendationService(CommitmentRecommendationRepository repository,
                                           CostExplorerClientProvider clientProvider,
                                           CostDayBoundary dayBoundary) {
        this.repository = repository;
        this.clientProvider = clientProvider;
        this.dayBoundary = dayBoundary;
    }

    public boolean isAvailable() {
        return enabled && clientProvider.isConfigured();
    }

    /** Captures today's three recommendations; returns how many rows were stored. */
    public int capture() {
        if (!isAvailable()) {
            log.debug("Commitment recommendations disabled or AWS unavailable; skipping");
            return 0;
        }
        TermInYears termInYears = enumOr(TermInYears.fromValue(term), TermInYears.ONE_YEAR);
        PaymentOption payment = enumOr(PaymentOption.fromValue(paymentOption), PaymentOption.NO_UPFRONT);
        LookbackPeriodInDays lookbackDays = enumOr(LookbackPeriodInDays.fromValue(lookback), LookbackPeriodInDays.THIRTY_DAYS);
        Date today = Date.valueOf(dayBoundary.today());

        int stored = 0;
        for (SupportedSavingsPlansType type : List.of(SupportedSavingsPlansType.COMPUTE_SP, SupportedSavingsPlansType.EC2_INSTANCE_SP)) {
            CommitmentRecommendation.Type recType = type == SupportedSavingsPlansType.COMPUTE_SP
                    ? CommitmentRecommendation.Type.COMPUTE_SP : CommitmentRecommendation.Type.EC2_INSTANCE_SP;
            try {
                CommitmentRecommendation row = row(today, recType, termInYears, payment, lookbackDays);
                fillSavingsPlans(row, type, termInYears, payment, lookbackDays);
                repository.save(row);
                stored++;
            } catch (Exception e) {
                log.warn("Could not fetch {} recommendations (may be missing ce:GetSavingsPlansPurchaseRecommendation): {}",
                        recType, e.getMessage());
            }
        }
        try {
            CommitmentRecommendation row = row(today, CommitmentRecommendation.Type.EC2_RI, termInYears, payment, lookbackDays);
            fillReservations(row, termInYears, payment, lookbackDays);
            repository.save(row);
            stored++;
        } catch (Exception e) {
            log.warn("Could not fetch EC2 Reserved Instance recommendations (may be missing ce:GetReservationPurchaseRecommendation): {}",
                    e.getMessage());
        }
        log.info("Commitment recommendations captured: {} of 3", stored);
        return stored;
    }

    /** Today's row for the key, existing or new. */
    private CommitmentRecommendation row(Date day, CommitmentRecommendation.Type type, TermInYears termInYears,
                                         PaymentOption payment, LookbackPeriodInDays lookbackDays) {
        CommitmentRecommendation row = repository.findByGeneratedOnAndRecTypeAndTermAndPaymentOption(
                day, type, termInYears.toString(), payment.toString()).orElseGet(() -> {
                    CommitmentRecommendation r = new CommitmentRecommendation();
                    r.setRecId(UUID.randomUUID().toString());
                    r.setGeneratedOn(day);
                    r.setRecType(type);
                    r.setTerm(termInYears.toString());
                    r.setPaymentOption(payment.toString());
                    return r;
                });
        row.setLookbackDays(lookbackDays == LookbackPeriodInDays.SEVEN_DAYS ? 7 : lookbackDays == LookbackPeriodInDays.SIXTY_DAYS ? 60 : 30);
        return row;
    }

    private void fillSavingsPlans(CommitmentRecommendation row, SupportedSavingsPlansType type, TermInYears termInYears,
                                  PaymentOption payment, LookbackPeriodInDays lookbackDays) {
        SavingsPlansPurchaseRecommendationSummary summary = null;
        List<SavingsPlansPurchaseRecommendationDetail> details = new ArrayList<>();
        String token = null;
        do {
            GetSavingsPlansPurchaseRecommendationResponse response = clientProvider.client().getSavingsPlansPurchaseRecommendation(
                    GetSavingsPlansPurchaseRecommendationRequest.builder()
                            .savingsPlansType(type).termInYears(termInYears).paymentOption(payment)
                            .lookbackPeriodInDays(lookbackDays).nextPageToken(token).build());
            SavingsPlansPurchaseRecommendation rec = response.savingsPlansPurchaseRecommendation();
            if (rec != null) {
                if (summary == null && rec.savingsPlansPurchaseRecommendationSummary() != null) {
                    summary = rec.savingsPlansPurchaseRecommendationSummary();
                }
                details.addAll(rec.savingsPlansPurchaseRecommendationDetails());
            }
            token = response.nextPageToken();
        } while (token != null && !token.isEmpty());

        row.setHourlyCommitment(summary == null ? null : decimal(summary.hourlyCommitmentToPurchase(), 4));
        row.setEstimatedMonthlySavings(summary == null ? null : decimal(summary.estimatedMonthlySavingsAmount(), 2));
        row.setEstimatedSavingsPercent(summary == null ? null : decimal(summary.estimatedSavingsPercentage(), 2));
        row.setEstimatedOnDemandCost(summary == null ? null : decimal(summary.currentOnDemandSpend(), 2));
        row.setUpfrontCost(details.isEmpty() ? null : sum(details.stream().map(SavingsPlansPurchaseRecommendationDetail::upfrontCost).toList()));

        List<Map<String, Object>> top = details.stream()
                .sorted(Comparator.comparing((SavingsPlansPurchaseRecommendationDetail d) -> number(d.estimatedMonthlySavingsAmount())).reversed())
                .limit(TOP_DETAILS)
                .map(d -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("instanceFamily", d.savingsPlansDetails() == null ? null : d.savingsPlansDetails().instanceFamily());
                    line.put("region", d.savingsPlansDetails() == null ? null : d.savingsPlansDetails().region());
                    line.put("hourlyCommitment", decimal(d.hourlyCommitmentToPurchase(), 4));
                    line.put("estimatedMonthlySavings", decimal(d.estimatedMonthlySavingsAmount(), 2));
                    line.put("estimatedAverageUtilization", decimal(d.estimatedAverageUtilization(), 2));
                    return line;
                }).toList();
        row.setDetails(json(top));
    }

    private void fillReservations(CommitmentRecommendation row, TermInYears termInYears, PaymentOption payment,
                                  LookbackPeriodInDays lookbackDays) {
        List<ReservationPurchaseRecommendationDetail> details = new ArrayList<>();
        String token = null;
        do {
            GetReservationPurchaseRecommendationResponse response = clientProvider.client().getReservationPurchaseRecommendation(
                    GetReservationPurchaseRecommendationRequest.builder()
                            .service(EC2_SERVICE).termInYears(termInYears).paymentOption(payment)
                            .lookbackPeriodInDays(lookbackDays).nextPageToken(token).build());
            for (ReservationPurchaseRecommendation rec : response.recommendations()) {
                details.addAll(rec.recommendationDetails());
            }
            token = response.nextPageToken();
        } while (token != null && !token.isEmpty());

        BigDecimal savings = sum(details.stream().map(ReservationPurchaseRecommendationDetail::estimatedMonthlySavingsAmount).toList());
        BigDecimal onDemand = sum(details.stream().map(ReservationPurchaseRecommendationDetail::estimatedMonthlyOnDemandCost).toList());
        row.setHourlyCommitment(null); // RIs are bought as instances, not as an hourly commitment
        row.setUpfrontCost(details.isEmpty() ? null : sum(details.stream().map(ReservationPurchaseRecommendationDetail::upfrontCost).toList()));
        row.setEstimatedMonthlySavings(details.isEmpty() ? null : savings);
        row.setEstimatedOnDemandCost(details.isEmpty() ? null : onDemand);
        row.setEstimatedSavingsPercent(onDemand.signum() > 0
                ? savings.multiply(BigDecimal.valueOf(100)).divide(onDemand, 2, RoundingMode.HALF_UP) : null);

        List<Map<String, Object>> top = details.stream()
                .sorted(Comparator.comparing((ReservationPurchaseRecommendationDetail d) -> number(d.estimatedMonthlySavingsAmount())).reversed())
                .limit(TOP_DETAILS)
                .map(d -> {
                    EC2InstanceDetails ec2 = d.instanceDetails() == null ? null : d.instanceDetails().ec2InstanceDetails();
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("instanceFamily", ec2 == null ? null : ec2.family());
                    line.put("instanceType", ec2 == null ? null : ec2.instanceType());
                    line.put("region", ec2 == null ? null : ec2.region());
                    line.put("count", decimal(d.recommendedNumberOfInstancesToPurchase(), 0));
                    line.put("estimatedMonthlySavings", decimal(d.estimatedMonthlySavingsAmount(), 2));
                    return line;
                }).toList();
        row.setDetails(json(top));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static <T extends Enum<T>> T enumOr(T value, T fallback) {
        return value == null || "UNKNOWN_TO_SDK_VERSION".equals(value.name()) ? fallback : value;
    }

    private static BigDecimal decimal(String value, int scale) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal number(String value) {
        BigDecimal v = decimal(value, 4);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal sum(List<String> values) {
        return values.stream().map(CommitmentRecommendationService::number).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
