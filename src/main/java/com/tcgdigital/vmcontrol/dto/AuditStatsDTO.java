package com.tcgdigital.vmcontrol.dto;

/**
 * Totals for the same filters as an audit-log page (E11-T04): never computed from one page.
 * {@code successRate} is a whole percentage, 0 when there are no rows; the top user and
 * environment are null when nothing matches.
 */
public record AuditStatsDTO(long total, long failures, int successRate, Top topUser, Top topEnvironment) {

    public record Top(String id, String name, long count) {
    }
}
