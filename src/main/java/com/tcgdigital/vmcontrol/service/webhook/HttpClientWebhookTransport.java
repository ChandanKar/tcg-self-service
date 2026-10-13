package com.tcgdigital.vmcontrol.service.webhook;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Webhook POSTs with the JDK HTTP client (E34-T06): redirects are never followed (a redirect to an
 * internal address would bypass the URL check), the response body is discarded, and the request
 * times out after webhooks.timeout-ms.
 */
@Component
public class HttpClientWebhookTransport implements WebhookTransport {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Response post(URI url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return new Response(response.statusCode(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, "Interrupted");
        } catch (Exception e) {
            return new Response(0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }
}
