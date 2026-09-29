package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.EksSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for periodic EKS cluster and node group synchronisation. Two independently-gated
 * concerns share one cadence:
 * <ul>
 *   <li>{@code eks.sync.auto-register.enabled} (default false) — silently registers newly
 *       discovered clusters as Environments. Off by default since this creates organizational
 *       objects (environments that then need access grants, appear in the self-service UI, etc.)
 *       without human review; new clusters are meant to be registered via the "Create
 *       Environment &gt; EKS" picker instead.</li>
 *   <li>{@code eks.sync.status-refresh.enabled} (default true) — refreshes node-group
 *       status/drift for EKS environments already registered. Safe and non-destructive, so it
 *       stays on by default regardless of the auto-register setting.</li>
 * </ul>
 * The manual "sync now" admin action ({@code EksSyncService.syncAllEksClusters}) always does
 * both, unconditionally — these flags only gate the automatic scheduled cycle.
 */
@Component
public class EksSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EksSyncScheduler.class);

    private final EksSyncService eksSyncService;

    @Value("${eks.sync.auto-register.enabled:false}")
    private boolean autoRegisterEnabled;

    @Value("${eks.sync.status-refresh.enabled:true}")
    private boolean statusRefreshEnabled;

    public EksSyncScheduler(EksSyncService eksSyncService) {
        this.eksSyncService = eksSyncService;
    }

    @Scheduled(fixedRateString = "${eks.sync.interval:300000}",
               initialDelayString = "${eks.sync.initial-delay:60000}")
    public void scheduledEksSync() {
        if (!autoRegisterEnabled && !statusRefreshEnabled) {
            return;
        }
        log.info("Scheduled EKS sync triggered (auto-register={}, status-refresh={})",
                autoRegisterEnabled, statusRefreshEnabled);
        try {
            if (autoRegisterEnabled) {
                eksSyncService.autoDiscoverClusters();
            }
            if (statusRefreshEnabled) {
                int synced = eksSyncService.syncRegisteredEksEnvironments();
                log.info("EKS status refresh completed — {} node group(s) processed", synced);
            }
        } catch (Exception e) {
            log.error("Error during scheduled EKS sync: {}", e.getMessage(), e);
        }
    }
}
