package com.tcgdigital.vmcontrol.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.model.ChatProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Microsoft Teams Workflows / incoming webhook (E24-T01): an Adaptive Card 1.4 in a message
 * attachment, with the fields as a FactSet and the actions as Action.OpenUrl buttons. Any 2xx
 * (Workflows answers 202) is success.
 */
@Component
public class TeamsWebhookSender extends AbstractWebhookSender {

    public TeamsWebhookSender(@Qualifier("chatRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    @Override
    public ChatProvider provider() {
        return ChatProvider.TEAMS;
    }

    private static String color(ChatMessage.Severity severity) {
        return switch (severity) {
            case CRITICAL -> "Attention";
            case WARNING -> "Warning";
            case INFO -> "Default";
        };
    }

    @Override
    protected ObjectNode payload(ChatMessage message) {
        ObjectNode root = objectMapper.createObjectNode().put("type", "message");
        ObjectNode attachment = root.putArray("attachments").addObject()
                .put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.putNull("contentUrl");
        ObjectNode card = attachment.putObject("content")
                .put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
                .put("type", "AdaptiveCard")
                .put("version", "1.4");
        ArrayNode body = card.putArray("body");
        body.addObject().put("type", "TextBlock").put("text", message.title() == null ? "" : message.title())
                .put("weight", "Bolder").put("size", "Medium").put("wrap", true).put("color", color(message.severity()));
        if (message.text() != null && !message.text().isBlank()) {
            body.addObject().put("type", "TextBlock").put("text", message.text()).put("wrap", true);
        }
        if (!message.fields().isEmpty()) {
            ArrayNode facts = body.addObject().put("type", "FactSet").putArray("facts");
            message.fields().forEach(f -> facts.addObject().put("title", f.label()).put("value", f.value() == null ? "" : f.value()));
        }
        if (message.linkUrl() != null && !message.linkUrl().isBlank()) {
            body.addObject().put("type", "TextBlock").put("text", "[Open in VM Control](" + message.linkUrl() + ")")
                    .put("wrap", true).put("isSubtle", true);
        }
        if (!message.actions().isEmpty()) {
            ArrayNode actions = card.putArray("actions");
            for (ChatAction action : message.actions()) {
                ObjectNode a = actions.addObject().put("type", "Action.OpenUrl").put("title", action.label()).put("url", action.url());
                if ("primary".equals(action.style())) {
                    a.put("style", "positive");
                } else if ("danger".equals(action.style())) {
                    a.put("style", "destructive");
                }
            }
        }
        return root;
    }
}
