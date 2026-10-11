package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.model.ChatProvider;

/** Posts a {@link ChatMessage} to one provider's incoming webhook (E24-T01). */
public interface ChatSender {

    ChatProvider provider();

    /**
     * Sends the message. channelLabel (e.g. "#team-platform") is only used in logs: the webhook URL
     * is a secret and is never logged.
     */
    void send(String webhookUrl, ChatMessage message, String channelLabel) throws ChatDeliveryException;

    default void send(String webhookUrl, ChatMessage message) throws ChatDeliveryException {
        send(webhookUrl, message, null);
    }
}
