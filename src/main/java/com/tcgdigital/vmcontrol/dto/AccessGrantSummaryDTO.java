package com.tcgdigital.vmcontrol.dto;

/**
 * Counts for the access management header (U10-T01): grants in force now in the reviewer's
 * environments, per level (ADMIN = admins, USER = operators, VIEWER = view only), and how many of
 * them end within 7 days.
 */
public record AccessGrantSummaryDTO(long total, long admins, long operators, long viewers, long endingWithin7Days) {
}
