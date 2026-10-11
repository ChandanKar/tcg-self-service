package com.tcgdigital.vmcontrol.model;

/** One-click actions a chat message can offer (E24-T01); each opens a signed confirm page. */
public enum ChatActionType {
    EXTEND_LEASE,
    SNOOZE_IDLE_STOP,
    APPROVE_ACCESS_REQUEST,
    STOP_ENVIRONMENT
}
