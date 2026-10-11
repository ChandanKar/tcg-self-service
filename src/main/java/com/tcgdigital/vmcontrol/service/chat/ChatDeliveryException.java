package com.tcgdigital.vmcontrol.service.chat;

/**
 * A chat message could not be delivered (E24-T01). The message never contains the webhook URL
 * (it is a secret), and no cause is attached because HTTP client exceptions quote the URL.
 */
public class ChatDeliveryException extends RuntimeException {

    public ChatDeliveryException(String message) {
        super(message);
    }
}
