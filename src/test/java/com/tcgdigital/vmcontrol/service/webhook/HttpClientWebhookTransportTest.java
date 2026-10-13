package com.tcgdigital.vmcontrol.service.webhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The real transport (E34-T06) against a local server: redirects are answered, never followed. */
class HttpClientWebhookTransportTest {

    private HttpServer server;
    private final AtomicInteger internalHits = new AtomicInteger();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedSignature = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/internal", exchange -> {
            internalHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-VmControl-Signature"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void aRedirectIsReturnedNotFollowed() {
        WebhookTransport.Response r = new HttpClientWebhookTransport().post(url("/redirect"), Map.of(), "{}", Duration.ofSeconds(5));

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.ok()).isFalse();
        assertThat(internalHits.get()).isZero();
    }

    @Test
    void theBodyAndHeadersArriveAsSent() {
        WebhookTransport.Response r = new HttpClientWebhookTransport().post(url("/ok"),
                Map.of("X-VmControl-Signature", "t=1,v1=abc"), "{\"type\":\"ping\"}", Duration.ofSeconds(5));

        assertThat(r.ok()).isTrue();
        assertThat(receivedBody.get()).isEqualTo("{\"type\":\"ping\"}");
        assertThat(receivedSignature.get()).isEqualTo("t=1,v1=abc");
    }

    @Test
    void anUnreachableReceiverIsStatusZeroWithAnError() {
        int port = server.getAddress().getPort();
        server.stop(0);
        WebhookTransport.Response r = new HttpClientWebhookTransport().post(URI.create("http://127.0.0.1:" + port + "/ok"),
                Map.of(), "{}", Duration.ofSeconds(2));

        assertThat(r.status()).isZero();
        assertThat(r.error()).isNotBlank();
    }
}
