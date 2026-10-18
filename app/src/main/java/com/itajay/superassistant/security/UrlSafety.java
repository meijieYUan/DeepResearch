package com.itajay.superassistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Shared outbound-URL guard.
 *
 * <p>Both user/model-supplied download URLs and crawled web pages can be steered at
 * loopback, private networks, or the cloud metadata endpoint. Every outbound hop —
 * including each HTTP redirect — must pass {@link #rejectionReason(URI)} before a
 * connection is opened.</p>
 *
 * <p>The check is done at name-resolution time; DNS rebinding between this check and
 * the connect is out of scope.</p>
 */
public final class UrlSafety {

    private static final Logger log = LoggerFactory.getLogger(UrlSafety.class);

    private UrlSafety() {
    }

    /** Returns a human-readable rejection reason, or {@code null} when the URL is safe. */
    public static String rejectionReason(String url) {
        if (url == null || url.isBlank()) {
            return "missing URL";
        }
        try {
            return rejectionReason(new URI(url));
        } catch (URISyntaxException e) {
            return "invalid URL";
        }
    }

    public static String rejectionReason(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "unsupported scheme (only http/https allowed)";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "missing host";
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "unknown host";
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                log.warn("Blocked outbound request to non-public address {} ({})",
                        address.getHostAddress(), host);
                return "host resolves to a non-public address (blocked)";
            }
        }
        return null;
    }

    private static boolean isNonPublic(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(address)
                || isUniqueLocalIpv6(address)
                || isZeroNetwork(address);
    }

    /** 100.64.0.0/10 (RFC 6598 shared space). */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xC0) == 64;
    }

    /** fc00::/7 — IPv6 unique local addresses (the IPv6 equivalent of RFC 1918). */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** 0.0.0.0/8 — "this network", never a legitimate remote host. */
    private static boolean isZeroNetwork(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 0;
    }
}
