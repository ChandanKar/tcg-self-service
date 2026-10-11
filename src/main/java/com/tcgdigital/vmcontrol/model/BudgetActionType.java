package com.tcgdigital.vmcontrol.model;

/** What may happen when a budget is exhausted (E20-T01); actions need approval (E20-T03). */
public enum BudgetActionType {
    NONE,
    /** Block starts in the environment. */
    FREEZE_STARTS,
    /** Stop the environment. */
    STOP_ENVIRONMENT
}
