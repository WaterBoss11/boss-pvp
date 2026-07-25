package com.boss.pvp.relay;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Why a relay connection attempt failed — specifically, whether the host is up but the app isn't yet.
 *
 * <p>The relay runs on a free-tier host that spins the service down after 15 minutes without inbound HTTP
 * traffic (an established WebSocket does not count), so "the service is asleep and waking up" is a routine,
 * self-healing state and NOT an outage. The two look nothing alike at the socket level, which is what makes
 * them separable:
 *
 * <ul>
 *   <li><b>Cold start</b> — TLS and TCP succeed because the platform's edge proxy is always up; it just has
 *       no running app to hand the upgrade to, so it answers the HTTP upgrade with a 5xx (or a redirect to a
 *       loading page). That surfaces as {@link WebSocketHandshakeException} carrying a real HTTP response.</li>
 *   <li><b>Outage / offline</b> — nothing answers at all: DNS doesn't resolve, the connection is refused, or
 *       it times out before any HTTP exchange. No handshake response exists.</li>
 * </ul>
 *
 * <p>Deliberately conservative: a 4xx handshake response is NOT a cold start (that's the edge rejecting us —
 * a bad URL, a gateway auth rule), and an unrecognized failure falls through to {@link #OTHER} so it keeps
 * the ordinary backoff rather than being optimistically retried as a wake-up.
 *
 * <p>Pure and client-free so the classification is unit-testable without a live socket.
 */
public enum RelayFailure {
    /** Host reachable, app not running yet — the service is waking up. Poll /health and wait. */
    COLD_START,
    /** Nothing is answering: DNS, refused, or timed out. A real outage (or the user is offline). */
    OUTAGE,
    /** Anything else — treated like an ordinary failure with normal backoff. */
    OTHER;

    private RelayFailure() {}

    /** Classify the throwable a failed connect produced. Never throws; unknown causes map to {@link #OTHER}. */
    public static RelayFailure classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof WebSocketHandshakeException handshake) {
                return fromHandshakeStatus(statusOf(handshake));
            }
            if (c instanceof UnknownHostException
                || c instanceof UnresolvedAddressException
                || c instanceof ConnectException
                || c instanceof TimeoutException
                || c instanceof java.net.SocketTimeoutException) {
                return OUTAGE;
            }
            if (c instanceof CompletionException) continue;   // unwrap the future's wrapper
            if (c.getCause() == c) break;                     // self-referential cause: stop
        }
        return OTHER;
    }

    /**
     * A 5xx on the WebSocket upgrade means the edge answered but had no app behind it — the spin-up case.
     * 4xx is the edge deliberately refusing us, which waiting will not fix.
     */
    static RelayFailure fromHandshakeStatus(int status) {
        if (status >= 500 && status <= 599) return COLD_START;
        // A redirect to a "please wait" loading page is the same situation wearing a different status.
        if (status == 307 || status == 302 || status == 303) return COLD_START;
        return OTHER;
    }

    private static int statusOf(WebSocketHandshakeException e) {
        try {
            HttpResponse<?> r = e.getResponse();
            return r == null ? -1 : r.statusCode();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** True when waiting and retrying is the right response (as opposed to backing off quietly). */
    public boolean isWarmingUp() {
        return this == COLD_START;
    }

    /** Whether this failure is worth telling the user about at all, vs. logging quietly. */
    public boolean isUserVisible() {
        return this == COLD_START || this == OUTAGE;
    }

    /** True if the throwable is an IO-level failure we understand well enough to classify. */
    static boolean isIo(Throwable t) {
        return t instanceof IOException;
    }
}
