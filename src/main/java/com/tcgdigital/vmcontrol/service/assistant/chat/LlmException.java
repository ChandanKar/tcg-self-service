package com.tcgdigital.vmcontrol.service.assistant.chat;

/** The model provider failed or answered something unusable; the message is safe to log, not to show. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
