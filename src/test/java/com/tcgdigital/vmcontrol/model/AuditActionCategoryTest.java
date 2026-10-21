package com.tcgdigital.vmcontrol.model;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** U12-T01: the audit log's action categories. */
class AuditActionCategoryTest {

    @Test
    void everyActionIsInAtMostOneCategory() {
        Map<AuditAction, AuditActionCategory> seen = new EnumMap<>(AuditAction.class);
        for (AuditActionCategory category : AuditActionCategory.values()) {
            for (AuditAction action : category.actions()) {
                AuditActionCategory before = seen.put(action, category);
                assertThat(before).as("%s is in %s and %s", action, before, category).isNull();
            }
        }
    }

    @Test
    void automationIsTheRulesAndTheHolidayCalendars() {
        assertThat(AuditActionCategory.AUTOMATION.actions()).containsExactlyInAnyOrder(
                AuditAction.AUTOMATION_RULE_CREATED, AuditAction.AUTOMATION_RULE_UPDATED, AuditAction.AUTOMATION_RULE_DELETED,
                AuditAction.AUTOMATION_RULE_TRIGGERED, AuditAction.AUTOMATION_RULE_SKIPPED, AuditAction.AUTOMATION_RULE_FAILED,
                AuditAction.HOLIDAY_CALENDAR_CHANGED);
    }

    @Test
    void myActivityKeepsItsGroups() {
        // U09-T04: leases and held approvals count as starting and stopping in My activity.
        assertThat(AuditActionCategory.OPERATIONS.actions()).contains(AuditAction.LEASE_EXTENDED, AuditAction.APPROVAL_REQUESTED);
        assertThat(AuditActionCategory.LOCKS.actions()).isEqualTo(Set.of(AuditAction.LOCK_ACQUIRED, AuditAction.LOCK_RELEASED,
                AuditAction.LOCK_BROKEN, AuditAction.LOCK_EXPIRED, AuditAction.LOCK_EXTENDED));
    }

    @Test
    void labels() {
        assertThat(AuditActionCategory.OPERATIONS.label()).isEqualTo("Start and stop");
        assertThat(AuditActionCategory.SETTINGS.label()).isEqualTo("Settings");
        assertThat(AuditActionCategory.COST.label()).isEqualTo("Cost");
        for (AuditActionCategory c : AuditActionCategory.values()) {
            assertThat(c.actionNames()).isNotEmpty();
        }
    }
}
