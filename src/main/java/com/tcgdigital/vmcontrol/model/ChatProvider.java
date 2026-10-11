package com.tcgdigital.vmcontrol.model;

/**
 * Where chat notifications go (E24-T01). Microsoft Teams only for now; Slack is parked (PL-13)
 * and slots in as another {@code ChatSender}.
 */
public enum ChatProvider {
    TEAMS
}
