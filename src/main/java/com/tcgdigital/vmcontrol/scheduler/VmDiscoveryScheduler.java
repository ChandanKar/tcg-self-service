package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.VmDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vm.discovery.enabled", havingValue = "true", matchIfMissing = false)
public class VmDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(VmDiscoveryScheduler.class);

    private final VmDiscoveryService discoveryService;

    public VmDiscoveryScheduler(VmDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Scheduled(fixedRateString = "${vm.discovery.interval:600000}",
               initialDelayString = "${vm.discovery.initial-delay:120000}")
    public void scheduledDiscovery() {
        log.debug("Scheduled VM discovery triggered");
        try {
            int registered = discoveryService.discoverAndRegisterVms();
            if (registered > 0) {
                log.info("VM discovery registered {} new VM(s) pending admin review", registered);
            }
        } catch (Exception e) {
            log.error("Error during scheduled VM discovery: {}", e.getMessage(), e);
        }
    }
}