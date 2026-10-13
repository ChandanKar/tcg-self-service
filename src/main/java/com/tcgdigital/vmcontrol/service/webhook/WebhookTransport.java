package com.tcgdigital.vmcontrol.service.webhook;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Sends one webhook POST (E34-T06); redirects are never followed. */
public interface WebhookTransport {

    /** The receiver's answer: a status code, or 0 with an error when nothing came back. */
    record Response(int status, String error) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    Response post(URI url, Map<String, String> headers, String body, Duration timeout);
}
