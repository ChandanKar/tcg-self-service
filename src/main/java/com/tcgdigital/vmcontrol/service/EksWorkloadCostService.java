package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.EksWorkloadCostDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.EksWorkloadCostDaily;
import com.tcgdigital.vmcontrol.repository.EksWorkloadCostDailyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Namespace and workload cost of an EKS environment from the ingested Split Cost Allocation Data
 * (E26-T04/T05): the last {@code days} complete UTC days, namespaces by cost, workloads within.
 */
@Service
public class EksWorkloadCostService {

    public static final int MAX_DAYS = 90;

    private final EksWorkloadCostDailyRepository costRepository;
    private final EnvironmentRepository environmentRepository;
    Clock clock = Clock.systemUTC();

    public EksWorkloadCostService(EksWorkloadCostDailyRepository costRepository, EnvironmentRepository environmentRepository) {
        this.costRepository = costRepository;
        this.environmentRepository = environmentRepository;
    }

    @Transactional(readOnly = true)
    public EksWorkloadCostDTO forEnvironment(String environmentId, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new ValidationException("days must be between 1 and " + MAX_DAYS);
        }
        if (environmentId == null || !environmentRepository.existsById(environmentId)) {
            throw new ResourceNotFoundException("Environment", environmentId);
        }
        LocalDate to = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        LocalDate from = to.minusDays(days - 1L);
        List<EksWorkloadCostDaily> rows = costRepository.findByEnvironmentIdAndUsageDateBetweenOrderByUsageDateAsc(
                environmentId, Date.valueOf(from), Date.valueOf(to));

        // namespace -> workload -> [split, unused]
        Map<String, Map<String, BigDecimal[]>> byNamespace = new HashMap<>();
        BigDecimal split = BigDecimal.ZERO;
        BigDecimal unused = BigDecimal.ZERO;
        for (EksWorkloadCostDaily r : rows) {
            BigDecimal[] sums = byNamespace.computeIfAbsent(r.getNamespace(), n -> new HashMap<>())
                    .computeIfAbsent(r.getWorkload(), w -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(r.getSplitCost());
            sums[1] = sums[1].add(r.getUnusedCost());
            split = split.add(r.getSplitCost());
            unused = unused.add(r.getUnusedCost());
        }
        BigDecimal whole = split.add(unused);

        List<EksWorkloadCostDTO.NamespaceCost> namespaces = new ArrayList<>();
        byNamespace.forEach((namespace, workloads) -> {
            List<EksWorkloadCostDTO.WorkloadCost> items = new ArrayList<>();
            BigDecimal nsSplit = BigDecimal.ZERO;
            BigDecimal nsUnused = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal[]> w : workloads.entrySet()) {
                BigDecimal s = money(w.getValue()[0]);
                BigDecimal u = money(w.getValue()[1]);
                items.add(new EksWorkloadCostDTO.WorkloadCost(namespace, w.getKey(), s, u, share(s, whole)));
                nsSplit = nsSplit.add(w.getValue()[0]);
                nsUnused = nsUnused.add(w.getValue()[1]);
            }
            items.sort(Comparator.comparing(EksWorkloadCostDTO.WorkloadCost::splitCost).reversed()
                    .thenComparing(EksWorkloadCostDTO.WorkloadCost::workload));
            namespaces.add(new EksWorkloadCostDTO.NamespaceCost(namespace, money(nsSplit), money(nsUnused),
                    share(nsSplit, whole), items));
        });
        namespaces.sort(Comparator.comparing(EksWorkloadCostDTO.NamespaceCost::splitCost).reversed()
                .thenComparing(EksWorkloadCostDTO.NamespaceCost::namespace));
        return new EksWorkloadCostDTO(environmentId, days, from, to, money(split), money(unused), share(unused, whole), namespaces);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO.setScale(1)
                : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
