package com.boss.pvp.relay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Polls the relay's plain-HTTP {@code GET /health} endpoint (see BossRelay PROTOCOL.md) to find out when a
 * spun-down service has finished waking up.
 *
 * <p>Why HTTP rather than just retrying the WebSocket: on the relay's free-tier host it is an inbound <b>HTTP</b>
 * request that resets the idle timer and triggers the spin-up in the first place — an established WebSocket does
 * not count as activity. So polling /health both detects readiness AND is the thing that wakes the service,
 * whereas hammering the WebSocket upgrade only produces more 5xx handshake failures.
 *
 * <p>Everything except the actual request is pure, so URL derivation is unit-testable offline.
 */
public final class HealthProbe {

    private HealthProbe() {}

    /** The path the relay serves its constant "ok" on. Mirrors {@code HEALTH_PATH} in the relay's health.js. */
    public static final String HEALTH_PATH = "/health";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        // Don't follow redirects: while the host is still waking, a redirect to a loading page is NOT
        // readiness, and following it could yield a misleading 200 from the platform's own holding page.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /**
     * Turn the configured WebSocket URL into its health URL: {@code wss://host/...} &rarr;
     * {@code https://host/health} (and {@code ws://} &rarr; {@code http://}). Any path, query, or fragment on
     * the WebSocket URL is dropped — /health is served at the root of the same origin. Returns null if the
     * input isn't a usable ws URL. Pure/testable.
     */
    public static String healthUrlFor(String wsUrl) {
        if (wsUrl == null) return null;
        String s = wsUrl.trim();
        if (s.isEmpty()) return null;
        String scheme;
        if (s.regionMatches(true, 0, "wss://", 0, 6)) scheme = "https://";
        else if (s.regionMatches(true, 0, "ws://", 0, 5)) scheme = "http://";
        else return null;
        String rest = s.substring(s.indexOf("://") + 3);
        // Strip path/query/fragment — keep only authority (host[:port], and any userinfo the URL carried).
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { cut = i; break; }
        }
        String authority = rest.substring(0, cut);
        if (authority.isEmpty()) return null;
        return scheme + authority + HEALTH_PATH;
    }

    /**
     * One health check. True only on HTTP 200 — the relay's /health answers a constant 200 "ok", so anything
     * else (5xx while still booting, a redirect to a holding page, a timeout) means "not ready yet".
     * Never throws.
     */
    public static boolean isUp(String healthUrl, Duration timeout) {
        if (healthUrl == null) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(healthUrl))
                .timeout(timeout)
                .header("Cache-Control", "no-cache")
                .GET()
                .build();
            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200;
        } catch (Throwable t) {
            return false;
        }
    }
}
