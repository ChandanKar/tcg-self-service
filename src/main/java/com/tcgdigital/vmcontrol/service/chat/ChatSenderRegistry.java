package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.model.ChatProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves the chat sender for a provider (E24-T01), like CloudProviderFactory. */
@Component
public class ChatSenderRegistry {

    private final Map<ChatProvider, ChatSender> senders = new EnumMap<>(ChatProvider.class);

    public ChatSenderRegistry(List<ChatSender> senders) {
        senders.forEach(s -> this.senders.put(s.provider(), s));
    }

    public ChatSender get(ChatProvider provider) {
        ChatSender sender = senders.get(provider);
        if (sender == null) {
            throw new IllegalArgumentException("No chat sender for " + provider);
        }
        return sender;
    }
}
