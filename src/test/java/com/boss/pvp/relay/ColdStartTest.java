package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cold-start path's two pure pieces: telling "the host is asleep and waking" apart from "nothing is
 * there", and deriving the /health URL to poll. Both must be right offline, because getting the first one
 * wrong means either claiming the server is starting when the user's wifi is off, or sitting through a
 * silent backoff when it really was just a spin-up.
 */
class ColdStartTest {

    // ---- RelayFailure.classify: cold start vs outage -------------------------------------------------

    @Test
    void fiveHundredsOnTheUpgradeAreAColdStart() {
        // The edge proxy is up (TLS/TCP fine) but has no app to hand the upgrade to.
        for (int status : new int[]{500, 502, 503, 504, 599}) {
            assertEquals(RelayFailure.COLD_START, RelayFailure.fromHandshakeStatus(status),
                "HTTP " + status + " on the upgrade means the app isn't running yet");
        }
        // A redirect to a "waking up" holding page is the same situation with a different status.
        for (int status : new int[]{302, 303, 307}) {
            assertEquals(RelayFailure.COLD_START, RelayFailure.fromHandshakeStatus(status));
        }
    }

    @Test
    void clientErrorsOnTheUpgradeAreNotAColdStart() {
        // 4xx is the edge deliberately refusing us — a wrong URL or a gateway rule. Waiting won't fix it,
        // and telling the user "starting up, ~15-60s" would be a lie that never resolves.
        for (int status : new int[]{400, 401, 403, 404, 426, 429}) {
            assertEquals(RelayFailure.OTHER, RelayFailure.fromHandshakeStatus(status),
                "HTTP " + status + " is not a spin-up");
        }
        assertEquals(RelayFailure.OTHER, RelayFailure.fromHandshakeStatus(200));
        assertEquals(RelayFailure.OTHER, RelayFailure.fromHandshakeStatus(-1), "no response at all");
    }

    @Test
    void nothingAnsweringIsAnOutageNotAColdStart() {
        // These are the "user is offline / host is gone" cases. They must never be reported as a spin-up.
        for (Throwable t : new Throwable[]{
                new UnknownHostException("bossrelay.onrender.com"),
                new UnresolvedAddressException(),
                new ConnectException("Connection refused"),
                new TimeoutException("timed out"),
                new java.net.SocketTimeoutException("read timed out")}) {
            assertEquals(RelayFailure.OUTAGE, RelayFailure.classify(t),
                t.getClass().getSimpleName() + " is an outage");
        }
    }

    @Test
    void causesAreUnwrappedThroughFutureAndIoWrappers() {
        // buildAsync delivers failures wrapped in a CompletionException, often more than one layer deep.
        assertEquals(RelayFailure.OUTAGE,
            RelayFailure.classify(new CompletionException(new ConnectException("refused"))));
        assertEquals(RelayFailure.OUTAGE,
            RelayFailure.classify(new CompletionException(
                new java.io.IOException("wrapped", new UnknownHostException("host")))));
    }

    @Test
    void unknownFailuresFallBackToOther() {
        // Anything we don't understand keeps the ordinary backoff rather than being optimistically
        // retried as a wake-up.
        assertEquals(RelayFailure.OTHER, RelayFailure.classify(new RuntimeException("???")));
        assertEquals(RelayFailure.OTHER, RelayFailure.classify(null));
    }

    @Test
    void selfReferentialCauseDoesNotLoopForever() {
        // A throwable whose cause is itself must not hang the classifier.
        RuntimeException loop = new RuntimeException("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertEquals(RelayFailure.OTHER, RelayFailure.classify(loop));
    }

    @Test
    void onlyColdStartAsksTheCallerToWait() {
        assertTrue(RelayFailure.COLD_START.isWarmingUp());
        assertFalse(RelayFailure.OUTAGE.isWarmingUp(), "an outage must not sit in a wake-up poll loop");
        assertFalse(RelayFailure.OTHER.isWarmingUp());
    }

    // ---- HealthProbe.healthUrlFor: ws URL -> health URL ----------------------------------------------

    @Test
    void healthUrlSwapsSchemeAndTargetsTheRootHealthPath() {
        assertEquals("https://bossrelay.onrender.com/health",
            HealthProbe.healthUrlFor("wss://bossrelay.onrender.com"));
        assertEquals("http://127.0.0.1:8099/health",
            HealthProbe.healthUrlFor("ws://127.0.0.1:8099"));
        assertEquals("https://relay.example.com:8443/health",
            HealthProbe.healthUrlFor("wss://relay.example.com:8443"));
    }

    @Test
    void healthUrlDropsAnyPathQueryOrFragment() {
        // /health is served at the origin root, not under the socket's path.
        assertEquals("https://bossrelay.onrender.com/health",
            HealthProbe.healthUrlFor("wss://bossrelay.onrender.com/socket"));
        assertEquals("https://bossrelay.onrender.com/health",
            HealthProbe.healthUrlFor("wss://bossrelay.onrender.com/?api-key=abc"));
        assertEquals("https://bossrelay.onrender.com/health",
            HealthProbe.healthUrlFor("wss://bossrelay.onrender.com#frag"));
    }

    @Test
    void healthUrlToleratesSurroundingWhitespaceAndSchemeCase() {
        assertEquals("https://bossrelay.onrender.com/health",
            HealthProbe.healthUrlFor("  wss://bossrelay.onrender.com  "));
        assertEquals("https://bossrelay.onrender.com/health",
            HealthProbe.healthUrlFor("WSS://bossrelay.onrender.com"));
    }

    @Test
    void healthUrlRejectsAnythingThatIsNotAWebSocketUrl() {
        for (String bad : new String[]{
                null, "", "   ", "https://bossrelay.onrender.com", "bossrelay.onrender.com",
                "wss://", "ws://", "ftp://host"}) {
            assertNull(HealthProbe.healthUrlFor(bad), "should not produce a health URL: " + bad);
        }
    }

    @Test
    void healthProbeReportsDownRatherThanThrowingOnAJunkUrl() {
        // isUp must never propagate an exception into the reconnect scheduler.
        assertFalse(HealthProbe.isUp(null, java.time.Duration.ofMillis(50)));
        assertFalse(HealthProbe.isUp("not a url", java.time.Duration.ofMillis(50)));
    }

    // ---- the user-facing messages must not lie about which state we're in ---------------------------

    @Test
    void coldStartMessageStatesTheEstimateAndOutageMessageDoesNot() {
        String cold = BossChatFormat.coldStart(15, 60);
        assertTrue(cold.contains("starting up"), cold);
        assertTrue(cold.contains("~15") && cold.contains("60s"), "shows the estimated range: " + cold);

        String down = BossChatFormat.unreachable();
        assertFalse(down.contains("starting up"), "an outage must not claim the server is starting: " + down);

        assertTrue(BossChatFormat.coldStartProgress(21, 7).contains("21s"), "progress shows elapsed time");
        assertTrue(BossChatFormat.coldStartReady(12).contains("awake"));
        assertTrue(BossChatFormat.coldStartTimedOut(120).contains("120s"));
    }
}
