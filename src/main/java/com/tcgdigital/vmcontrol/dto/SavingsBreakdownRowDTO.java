package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One cause, environment or team in a savings breakdown (E17-T06). {@code team}: an environment row's
 * team (U15-T05/T06 Savings page), null for causes and teams.
 */
public record SavingsBreakdownRowDTO(String key, String label, BigDecimal net, BigDecimal gross, BigDecimal hours, String team) {
    public SavingsBreakdownRowDTO(String key, String label, BigDecimal net, BigDecimal gross, BigDecimal hours) {
        this(key, label, net, gross, hours, null);
    }

    public SavingsBreakdownRowDTO withTeam(String team) {
        return new SavingsBreakdownRowDTO(key, label, net, gross, hours, team);
    }
}
