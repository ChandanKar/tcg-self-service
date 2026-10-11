package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Slack incoming webhook (E24-T01): Block Kit with a header, the text, the fields and URL buttons.
 * Slack answers "ok" when it accepts a message.
 */
@Component
public class SlackWebhookSender extends AbstractWebhookSender {

    private static final int MAX_HEADER = 150;
    private static final int MAX_FIELDS = 10;

    public SlackWebhookSender(@Qualifier("chatRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    @Override
    public ChatProvider provider() {
        return ChatProvider.SLACK;
    }

    /** Slack mrkdwn needs only &, < and > escaped. */
    static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    protected ObjectNode payload(ChatMessage message) {
        ObjectNode root = objectMapper.createObjectNode();
        String title = message.title() == null ? "" : message.title();
        root.put("text", title); // notification fallback
        ArrayNode blocks = root.putArray("blocks");

        ObjectNode header = blocks.addObject().put("type", "header");
        header.putObject("text").put("type", "plain_text")
                .put("text", title.length() > MAX_HEADER ? title.substring(0, MAX_HEADER - 1) + "…" : title);

        if (message.text() != null && !message.text().isBlank()) {
            blocks.addObject().put("type", "section")
                    .putObject("text").put("type", "mrkdwn").put("text", escape(message.text()));
        }

        if (!message.fields().isEmpty()) {
            ArrayNode fields = blocks.addObject().put("type", "section").putArray("fields");
            message.fields().stream().limit(MAX_FIELDS).forEach(f -> fields.addObject().put("type", "mrkdwn")
                    .put("text", "*" + escape(f.label()) + "*\n" + escape(f.value())));
        }

        if (!message.actions().isEmpty()) {
            ArrayNode elements = blocks.addObject().put("type", "actions").putArray("elements");
            for (ChatAction action : message.actions()) {
                ObjectNode button = elements.addObject().put("type", "button");
                button.putObject("text").put("type", "plain_text").put("text", action.label());
                button.put("url", action.url());
                if (action.type() != null) {
                    button.put("action_id", action.type().name().toLowerCase());
                }
                if ("primary".equals(action.style()) || "danger".equals(action.style())) {
                    button.put("style", action.style());
                }
            }
        }

        StringBuilder context = new StringBuilder("Severity: ").append(message.severity());
        if (message.linkUrl() != null && !message.linkUrl().isBlank()) {
            context.append(" · <").append(message.linkUrl()).append("|Open in VM Control>");
        }
        blocks.addObject().put("type", "context").putArray("elements").addObject()
                .put("type", "mrkdwn").put("text", context.toString());
        return root;
    }

    @Override
    protected boolean accepted(String body) {
        return body != null && "ok".equals(body.trim());
    }
}
