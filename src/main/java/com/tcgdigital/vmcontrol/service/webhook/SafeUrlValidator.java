package com.tcgdigital.vmcontrol.service.webhook;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Keeps webhooks off internal networks (E34-T06, SSRF): https only, no credentials in the URL, and
 * every address the host resolves to must be public, so loopback, private (10/8, 172.16/12,
 * 192.168/16, fc00::/7), carrier-grade NAT (100.64/10), link-local (169.254/16 with the cloud
 * metadata address, fe80::/10), multicast, unspecified and IPv4-mapped forms of them are refused.
 * Checked when a subscription is saved and again before every send (DNS can change in between).
 */
@Component
public class SafeUrlValidator {

    /** Resolves a host name to all its addresses. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;

    public SafeUrlValidator() {
        this(InetAddress::getAllByName);
    }

    public SafeUrlValidator(Resolver resolver) {
        this.resolver = resolver;
    }

    /** The parsed URL when it is safe to call; otherwise a ValidationException saying why. */
    public URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("The webhook URL is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("The webhook URL must use https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ValidationException("The webhook URL must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ValidationException("The webhook URL has no host");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal") || lower.endsWith(".local")) {
            throw new ValidationException("The webhook URL points at an internal host");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException e) {
            throw new ValidationException("The webhook host does not resolve: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new ValidationException("The webhook host does not resolve: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new ValidationException("The webhook host resolves to a private or reserved address");
            }
        }
        return uri;
    }

    /** True for any address a webhook must never reach. */
    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8
                    || first == 10                              // 10/8
                    || (first == 100 && second >= 64 && second <= 127) // 100.64/10 carrier-grade NAT
                    || (first == 169 && second == 254)          // 169.254/16 link-local, cloud metadata
                    || (first == 172 && second >= 16 && second <= 31)  // 172.16/12
                    || (first == 192 && second == 168)          // 192.168/16
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0/24 protocol assignments
                    || (first == 198 && (second == 18 || second == 19))    // 198.18/15 benchmarking
                    || first >= 224;                            // multicast and reserved
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;              // fc00::/7 unique local
            if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80) return true; // fe80::/10 link-local
            boolean mapped = true;                              // ::ffff:a.b.c.d IPv4-mapped
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isBlocked(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
