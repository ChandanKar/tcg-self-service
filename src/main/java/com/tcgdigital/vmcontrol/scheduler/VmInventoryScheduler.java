package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.VmInventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vm.inventory.sync.enabled", havingValue = "true", matchIfMissing = true)
public class VmInventoryScheduler {

    private static final Logger log = LoggerFactory.getLogger(VmInventoryScheduler.class);
    private static final String LOCK_NAME = "vm_inventory_sync";

    private final VmInventoryService inventoryService;
    private final ScheduledJobLockService lockService;

    @Value("${vm.inventory.sync.enabled:true}")
    private boolean enabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private boolean live(String key, boolean current) {
        return settings == null ? current : settings.overrideOr(key, current);
    }

    public VmInventoryScheduler(VmInventoryService inventoryService, ScheduledJobLockService lockService) {
        this.inventoryService = inventoryService;
        this.lockService = lockService;
    }

    @Scheduled(fixedRateString = "${vm.inventory.sync.interval:3600000}",
            initialDelayString = "${vm.inventory.sync.initial-delay:60000}")
    public void scheduledInventorySync() {
        if (!live("vm.inventory.sync.enabled", enabled)) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                inventoryService.syncAllInventory();
            } catch (Exception e) {
                log.error("Error during scheduled VM inventory sync: {}", e.getMessage(), e);
            }
        });
    }
}
