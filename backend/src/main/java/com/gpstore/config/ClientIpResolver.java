package com.gpstore.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client address for rate limits and audit logs.
 *
 * {@code X-Forwarded-For} and {@code CF-Connecting-IP} are client-spoofable
 * unless the immediate TCP peer is a proxy we pin. Production Tomcat's
 * {@code RemoteAddr} is Traefik on the Docker network (RFC1918), not
 * Cloudflare. Traefik must list Cloudflare ranges in
 * {@code forwardedHeaders.trustedIPs} so it preserves Cloudflare's headers;
 * this class then trusts Traefik (and optional extra CIDRs) only.
 */
@Component
public class ClientIpResolver {

    /**
     * Immediate peers that may set forwarded-client headers. Docker/Traefik
     * live here. Do not put "the whole internet" in this list.
     */
    public static final String DEFAULT_TRUSTED_CIDRS =
            "127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    private final boolean trustForwardedHeaders;
    private final List<Cidr> trustedProxies;

    public ClientIpResolver(
            @Value("${rate-limit.trust-forwarded-for:false}") boolean trustForwardedHeaders,
            @Value("${rate-limit.trusted-proxies:127.0.0.1/32,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1/128}")
                    String trustedProxies) {
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustedProxies = parseCidrs(trustedProxies);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            remote = "unknown";
        }
        if (!trustForwardedHeaders || !isTrustedProxy(remote)) {
            return remote;
        }
        String cf = literalIp(request.getHeader("CF-Connecting-IP"));
        if (cf != null) {
            return cf;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            for (String hop : forwarded.split(",")) {
                String ip = literalIp(hop);
                if (ip != null) {
                    return ip;
                }
            }
        }
        return remote;
    }

    boolean isTrustedProxy(String ip) {
        byte[] addr = parseAddress(ip);
        if (addr == null) {
            return false;
        }
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    static String literalIp(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        byte[] addr = parseAddress(value);
        if (addr == null) {
            return null;
        }
        if (isUnspecified(addr)) {
            return null;
        }
        return value;
    }

    static List<Cidr> parseCidrs(String csv) {
        List<Cidr> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String spec = part.trim();
            if (spec.isEmpty()) {
                continue;
            }
            int slash = spec.lastIndexOf('/');
            String ip = slash < 0 ? spec : spec.substring(0, slash);
            byte[] addr = parseAddress(ip);
            if (addr == null) {
                continue;
            }
            int max = addr.length * 8;
            int prefix = slash < 0 ? max : Integer.parseInt(spec.substring(slash + 1));
            if (prefix < 0 || prefix > max) {
                continue;
            }
            out.add(new Cidr(addr, prefix));
        }
        return List.copyOf(out);
    }

    static byte[] parseAddress(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1);
            value = lower;
        }
        if (!value.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static boolean isUnspecified(byte[] addr) {
        for (byte b : addr) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefixBits / 8;
            int rem = prefixBits % 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (rem == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rem);
            return (addr[full] & mask) == (network[full] & mask);
        }
    }
}
