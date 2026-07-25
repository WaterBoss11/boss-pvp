package com.boss.pvp.relay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Thin transport wrapper over the JDK's built-in {@link java.net.http.WebSocket} (no third-party dependency,
 * per the design doc). It only moves text frames and reports lifecycle; all protocol logic lives in
 * {@link RelayManager}. Reconnection policy is the manager's job — this class just tells it when the socket
 * opened, delivered a full message, or closed.
 *
 * <p>Inbound text frames can arrive in fragments, so {@code onText} is accumulated until {@code last} before
 * a whole JSON message is handed up. Outbound sends are serialized through a future chain because
 * {@code WebSocket.sendText} must not be called again before the previous send completes.
 */
public final class RelayClient {

    /** Callbacks into the manager. All may be invoked on WebSocket executor threads, not the game thread. */
    public interface Handler {
        void onOpen();
        void onMessage(String json);
        void onClosed(String reason);

        /**
         * A connection attempt failed before the socket ever opened, with the raw cause — which
         * {@link RelayFailure} needs in order to tell a host that is merely waking up (5xx on the upgrade)
         * from one that isn't there at all. Defaults to the lossy string path so an implementation that
         * doesn't care is unaffected.
         */
        default void onConnectFailed(Throwable cause) {
            onClosed("connect failed: " + (cause == null ? "unknown" : cause.getMessage()));
        }
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final URI uri;
    private final Handler handler;
    private final StringBuilder buf = new StringBuilder();

    private volatile WebSocket ws;
    private volatile boolean closing = false;

    private final Object sendLock = new Object();
    private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);

    public RelayClient(URI uri, Handler handler) {
        this.uri = uri;
        this.handler = handler;
    }

    /**
     * Open the connection. On failure the handler's {@code onConnectFailed} fires with the raw cause, so the
     * manager can tell a waking-up host from an absent one before deciding how to retry.
     */
    public void connect() {
        try {
            HTTP.newWebSocketBuilder()
                .buildAsync(uri, new Listener())
                .whenComplete((socket, err) -> {
                    if (err != null) {
                        if (!closing) handler.onConnectFailed(err);
                    } else {
                        ws = socket;
                        // Closed while the handshake was still in flight — don't leave the socket open.
                        if (closing) {
                            try {
                                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                            } catch (Throwable ignored) {
                                // best effort
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            if (!closing) handler.onConnectFailed(t);
        }
    }

    /** Serialized text send; silently no-ops if not connected. */
    public void send(String text) {
        WebSocket w = ws;
        if (w == null || text == null) return;
        synchronized (sendLock) {
            sendChain = sendChain
                .handle((r, e) -> null)                    // ignore the previous result/error, keep the chain alive
                .thenCompose(ignored -> w.sendText(text, true));
        }
    }

    /**
     * Close intentionally and DETACH: once this is called the socket stops reporting anything to the
     * handler, so a deliberately-discarded connection can never drive the manager's state.
     *
     * <p>That detachment is the point. The manager replaces its client on every (re)connect, and the old
     * socket's close lands asynchronously afterwards — so without this its {@code onClosed} would fire
     * against the manager that has already moved on, scheduling a spurious reconnect and stomping the
     * {@code authed}/{@code status} of the connection that replaced it. A stale {@code authok} arriving
     * on the dead socket would be just as wrong, which is why {@code onText} is gated too.
     */
    public void close() {
        closing = true;
        WebSocket w = ws;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            if (closing) return;
            handler.onOpen();
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closing) return null;   // frames from a discarded socket are not this manager's business
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handler.onMessage(msg);
                } catch (Throwable ignored) {
                    // a bad frame must never kill the receive loop
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closing) return null;   // we asked for this close; the manager already knows
            handler.onClosed("closed " + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason));
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            if (closing) return;
            handler.onClosed("error: " + error.getMessage());
        }
    }
}
