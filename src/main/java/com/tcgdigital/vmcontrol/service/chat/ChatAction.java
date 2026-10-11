package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.model.ChatActionType;

/**
 * A button on a chat message (E24-T01): it opens {@code url}. style is "primary", "danger" or null.
 */
public record ChatAction(ChatActionType type, String label, String url, String style) {
}
