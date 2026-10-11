package com.tcgdigital.vmcontrol.service.chat;

import java.util.List;

/**
 * A provider-neutral chat message (E24-T01). title and text are plain text: each sender escapes
 * them for its own format. linkUrl, when set, points back to the app.
 */
public record ChatMessage(String title, String text, Severity severity, List<Field> fields, List<ChatAction> actions,
                          String linkUrl) {

    public enum Severity { INFO, WARNING, CRITICAL }

    /** A label/value pair shown as a fact. */
    public record Field(String label, String value) {}

    public ChatMessage {
        severity = severity == null ? Severity.INFO : severity;
        fields = fields == null ? List.of() : List.copyOf(fields);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
