package com.boss.pvp.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side brain of the boss-pvp chat relay (Phase 1: client↔client broadcast; no Discord, no moderation).
 * Owns the connection lifecycle, the Mojang auth handshake, the outbound scope state, reporting the current
 * Minecraft server (for server-only routing), and displaying inbound messages in chat.
 *
 * <p><b>Public gate:</b> connects whenever {@link RelayConfig#isConfigured()} (a relay URL is present — the
 * public build bakes one). Verified users join with no invite; unverified identities are gated relay-side. An
 * install with no URL at all never connects and shows no relay UI.
 *
 * <p><b>Scopes:</b> {@link Mode#OFF} lets chat behave normally; {@link Mode#GLOBAL} and {@link Mode#SERVER}
 * redirect what you type into chat to the relay (global = everyone connected; server = only addon users on the
 * SAME Minecraft server). Direct messages are sent via {@code ?bossaddon chat dm <user> <text>} and are
 * delivered only to that one recipient — never echoed, logged, or relayed anywhere else.
 *
 * <p>Threading: WebSocket callbacks land on HTTP-client threads; all Minecraft/chat access is bounced to the
 * game thread via {@link Minecraft#execute}. Reconnect/auth run on a single daemon scheduler.
 */
public final class RelayManager implements RelayClient.Handler {

    public enum Mode { OFF, GLOBAL, SERVER, PARTY, DM }

    private static final RelayManager INSTANCE = new RelayManager();
    public static RelayManager get() { return INSTANCE; }

    private RelayManager() {}

    private static final long BACKOFF_START_MS = 2_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;

    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "boss-pvp-relay");
        t.setDaemon(true);
        return t;
    });

    private volatile RelayClient client;
    private volatile Mode mode = Mode.OFF;
    // The remembered DM target for the DM send-scope: set by "?bossaddon chat dm <user>[ <msg>]" and reused by
    // the DM scope tab / DM scope so typed chat goes to them. Null until a DM has been addressed at least once.
    private volatile String dmTarget = null;
    private volatile boolean authed = false;
    private volatile boolean myVerified = true;   // whether OUR connection authenticated via Mojang
    private volatile boolean fatal = false;          // rejected (invite/allowlist/auth) — stop auto-retry until manual reconnect
    private volatile String status = "off";
    private volatile String lastServer = null;
    private final AtomicInteger backoffAttempt = new AtomicInteger(0);

    // Pending inbound party-warp request (consent gate): the connect happens ONLY on an explicit accept, and
    // only within this many ms of the request so a stale request can't silently move you later.
    private static final long WARP_TTL_MS = 120_000L;
    private volatile String pendingWarpFrom = null;
    private volatile String pendingWarpAddress = null;
    private volatile long pendingWarpAt = 0L;

    // ---- lifecycle -----------------------------------------------------------------------------------

    /** Call once on addon init. No-op unless a relay URL is configured (public builds bake one). */
    public void init() {
        RelayConfig.autoPopulateDefaults();   // seed relay.url (public build bakes it) on first launch; never clobbers a manual edit
        if (!RelayConfig.isConfigured()) {
            status = "disabled (no relay URL configured)";
            return;
        }
        connect();
    }

    /**
     * (Re)connect from scratch. Safe to call repeatedly.
     *
     * <p>The previous socket is closed FIRST. Without that, every reconnect leaked a live connection: the
     * relay would see one account holding several sockets, and since it resolves a username to the first
     * matching connection, party invites and warps could be delivered to an abandoned one — the user is
     * online, addressed correctly, and sees nothing. {@link RelayClient#close()} also detaches the old
     * socket's callbacks, so its asynchronous close can't reconnect us again or overwrite the new
     * connection's state.
     */
    public synchronized void connect() {
        if (!RelayConfig.isConfigured()) return;
        closeQuietly();   // never leave a previous socket open — one account, one connection
        fatal = false;
        authed = false;
        status = "connecting";
        String url = RelayConfig.url();
        try {
            client = new RelayClient(URI.create(url), this);
            client.connect();
        } catch (Throwable t) {
            status = "connect error";
            scheduleReconnect();
        }
    }

    /**
     * Full opt-out: close the connection and stay down. Auto-reconnect is suppressed both by {@code fatal} and
     * because the gate ({@link RelayConfig#isConfigured()}) is now off once the caller has persisted the opt-out.
     * Re-enabling calls {@link #connect()}, which clears {@code fatal}.
     */
    public synchronized void disconnect() {
        fatal = true;
        closeQuietly();
        authed = false;
        status = "disabled";
    }

    private void scheduleReconnect() {
        if (fatal || !RelayConfig.isConfigured()) return;
        int attempt = backoffAttempt.getAndIncrement();
        long delay = Math.min(BACKOFF_START_MS << Math.min(attempt, 5), BACKOFF_MAX_MS);
        status = "reconnecting in " + (delay / 1000) + "s";
        sched.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    // ---- transport callbacks (off-thread) ------------------------------------------------------------

    @Override public void onOpen() {
        status = "authenticating";
    }

    @Override public void onClosed(String reason) {
        authed = false;
        status = "disconnected";
        if (!fatal) scheduleReconnect();
    }

    @Override public void onMessage(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String t = str(o, "t");
            if (t == null) return;
            switch (t) {
                case "hello"  -> onHello(str(o, "serverId"), bool(o, "allowUnverified", false));
                case "authok" -> onAuthOk(bool(o, "verified", true));
                case "msg"    -> onInboundMessage(o);
                case "party"  -> onParty(o);
                case "warp"   -> onWarp(o);
                // Never render raw wire text: §-strip the server's system text before styling it.
                case "system" -> display(BossChatFormat.system(RelaySanitizer.sanitize(str(o, "text"))));
                case "error"  -> onError(str(o, "reason"));
                default -> { /* unknown type — ignore */ }
            }
        } catch (Throwable ignored) {
            // malformed frame — ignore, never trust the wire
        }
    }

    // ---- handshake -----------------------------------------------------------------------------------

    private void onHello(String serverId, boolean allowUnverified) {
        if (serverId == null) return;
        status = "authenticating";
        sched.execute(() -> {
            try {
                User user = Minecraft.getInstance().getUser();
                if (user == null) { closeQuietly(); return; }
                String name = user.getName();
                String uuid = user.getProfileId() == null ? "" : user.getProfileId().toString();
                String token = user.getAccessToken();

                // Verified first: try the Mojang handshake unless the user forced offline. If it succeeds we
                // authenticate as a verified (premium) account. If it fails — offline/non-premium account, or a
                // transient Mojang error — fall back to an unverified (self-reported) identity, but ONLY if the
                // relay allows it. This never silently "downgrades" a premium user on a verified-only relay.
                boolean joined = !RelayConfig.forceOffline()
                    && MojangAuth.joinServer(token, uuid, serverId);

                RelayClient c = client;
                if (c == null) return;

                if (joined) {
                    JsonObject auth = new JsonObject();
                    auth.addProperty("t", "auth");
                    auth.addProperty("username", name);
                    auth.addProperty("uuid", uuid);
                    auth.addProperty("invite", RelayConfig.invite());
                    auth.addProperty("server", currentServerId());
                    c.send(auth.toString());
                } else if (allowUnverified) {
                    JsonObject auth = new JsonObject();
                    auth.addProperty("t", "auth");
                    auth.addProperty("offline", true);
                    auth.addProperty("username", name);
                    auth.addProperty("invite", RelayConfig.invite());
                    auth.addProperty("server", currentServerId());
                    String pw = RelayConfig.password();   // claims/resumes the name if set (never sent for verified)
                    if (pw != null) auth.addProperty("password", pw);
                    c.send(auth.toString());
                    display(BossChatFormat.connectingUnverified());
                } else {
                    display(BossChatFormat.verifiedOnly(RelayConfig.forceOffline()));
                    fatal = true;   // won't fix on retry; user must ?bossaddon chat reconnect
                    closeQuietly();
                }
            } catch (Throwable t) {
                closeQuietly();
            }
        });
    }

    private void onAuthOk(boolean verified) {
        authed = true;
        myVerified = verified;
        fatal = false;
        backoffAttempt.set(0);
        status = verified ? "connected" : "connected (unverified)";
        lastServer = null;
        reportServer(true);
        display(verified ? BossChatFormat.connectedVerified() : BossChatFormat.connectedUnverified());
    }

    private void onError(String reason) {
        display(BossChatFormat.rejected(reason));
        // Auth/gate rejections won't fix themselves on retry — stop hammering until the user reconnects.
        if (reason != null) {
            String r = reason.toLowerCase();
            if (r.contains("allowlist") || r.contains("invite") || r.contains("auth")) fatal = true;
        }
    }

    // ---- outbound ------------------------------------------------------------------------------------

    /** True only when a typed chat line should be redirected to the relay (mode on AND actually connected). */
    public boolean shouldRedirectChat() {
        return mode != Mode.OFF && authed;
    }

    /** Redirect a typed chat line (from the ChatScreen mixin) to the relay using the current scope. */
    public void sendTyped(String text) {
        if (mode == Mode.DM) {
            if (dmTarget == null) { display(BossChatFormat.dmNoTarget()); return; }
            send("dm", dmTarget, text);
            return;
        }
        send(wireScope(mode), null, text);
    }

    /** The wire scope string for a send mode. OFF is never sent, so it maps to global for safety. Pure/testable. */
    public static String wireScope(Mode mode) {
        return switch (mode) {
            case SERVER -> "server";
            case PARTY -> "party";
            case DM -> "dm";
            default -> "global";   // GLOBAL (and OFF, which shouldRedirectChat already excludes)
        };
    }

    public void sendGlobal(String text) { send("global", null, text); }
    public void sendServer(String text) { send("server", null, text); }
    public void sendParty(String text) { send("party", null, text); }

    public void sendDm(String toUser, String text) {
        if (toUser != null && !toUser.isBlank()) dmTarget = toUser.trim();   // remember the last DM target
        send("dm", toUser, text);
    }

    /** The remembered DM target (or null), used by the DM scope + DM tab. */
    public String dmTarget() { return dmTarget; }

    // ---- party actions (invite / accept / decline / leave / list) ------------------------------------

    public void partyInvite(String user) { sendPartyAction("invite", user); }
    public void partyAccept()  { sendPartyAction("accept", null); }
    public void partyDecline() { sendPartyAction("decline", null); }
    public void partyLeave()   { sendPartyAction("leave", null); }
    public void partyList()    { sendPartyAction("list", null); }

    /** Send a {t:'party', action, to?} control frame. The relay enforces mute/rate-limit/membership. */
    private void sendPartyAction(String action, String to) {
        if (!authed) { display(BossChatFormat.notConnected()); return; }
        RelayClient c = client;
        if (c == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "party");
        o.addProperty("action", action);
        if (to != null) {
            String clean = RelaySanitizer.sanitize(to);
            if (clean.isEmpty()) return;
            o.addProperty("to", clean);
        }
        c.send(o.toString());
    }

    // ---- party warp (propose / accept / decline) -----------------------------------------------------
    // A warp is a REQUEST, never an automatic connect. The sender shares only their current server address
    // and (relay-side) which party member sent it; the recipient must explicitly accept before anything joins.

    /**
     * Propose a warp: share the server YOU are currently on with a party member (or the whole party when
     * {@code toUser} is null). Sends nothing but the address; the recipient decides. No-op if you aren't on a
     * joinable server.
     */
    public void warpPropose(String toUser) {
        if (!authed) { display(BossChatFormat.notConnected()); return; }
        String address = currentServerAddress();
        if (address == null) { display(BossChatFormat.warpNotOnServer()); return; }
        RelayClient c = client;
        if (c == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "warp");
        o.addProperty("action", "request");
        o.addProperty("address", address);
        if (toUser != null) {
            String clean = RelaySanitizer.sanitize(toUser);
            if (clean.isEmpty()) return;
            o.addProperty("to", clean);
        }
        c.send(o.toString());
        display(BossChatFormat.warpSent(toUser));
    }

    /** Accept the pending warp request: connect to the destination the requester named. The ONLY connect path. */
    public void warpAccept() {
        String addr = pendingWarpAddress;
        long at = pendingWarpAt;
        clearPendingWarp();
        if (addr == null || System.currentTimeMillis() - at > WARP_TTL_MS) {
            display(BossChatFormat.warpNonePending());
            return;
        }
        display(BossChatFormat.warpConnecting(addr));
        ServerConnect.connectTo(addr);   // clean vanilla ConnectScreen path; runs on the render thread
    }

    /** Decline the pending warp request (and tell the requester, courtesy). */
    public void warpDecline() {
        String from = pendingWarpFrom;
        boolean had = pendingWarpAddress != null;
        clearPendingWarp();
        if (!had) { display(BossChatFormat.warpNonePending()); return; }
        RelayClient c = client;
        if (c != null && authed && from != null) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "warp");
            o.addProperty("action", "decline");
            o.addProperty("to", from);
            c.send(o.toString());
        }
        display(BossChatFormat.warpDeclined());
    }

    private void clearPendingWarp() {
        pendingWarpFrom = null;
        pendingWarpAddress = null;
        pendingWarpAt = 0L;
    }

    /** Inbound warp events: a request to store+prompt (never auto-connect), or a decline from a member. */
    private void onWarp(JsonObject o) {
        String event = str(o, "event");
        if (event == null) return;
        switch (event) {
            case "request" -> {
                String from = str(o, "from");
                String address = str(o, "address");
                boolean fromVerified = bool(o, "fromVerified", true);
                // Only accept a well-formed address; a malformed/hostile value is dropped, never stored.
                if (from == null || !ServerConnect.isValid(address)) return;
                pendingWarpFrom = from;
                pendingWarpAddress = address.trim();
                pendingWarpAt = System.currentTimeMillis();
                display(BossChatFormat.warpRequest(from, fromVerified, pendingWarpAddress));
            }
            case "declined" -> display(BossChatFormat.warpDeclinedBy(str(o, "from"), bool(o, "fromVerified", true)));
            default -> { /* unknown warp event — ignore */ }
        }
    }

    /** The joinable address of the server we're currently on, or null (singleplayer / no server / unknown). */
    private String currentServerAddress() {
        try {
            ServerData sd = Minecraft.getInstance().getCurrentServer();
            if (sd == null || sd.ip == null || sd.ip.isBlank()) return null;
            String ip = sd.ip.strip();
            return ServerConnect.isValid(ip) ? ip : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void send(String scope, String to, String text) {
        if (!authed) { display(BossChatFormat.notConnected()); return; }
        String clean = RelaySanitizer.sanitize(text);
        if (clean.isEmpty()) return;
        RelayClient c = client;
        if (c == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "msg");
        o.addProperty("scope", scope);
        if (to != null) o.addProperty("to", to);
        o.addProperty("text", clean);
        c.send(o.toString());
        echoLocal(scope, to, clean);
    }

    // ---- server reporting (for server-only routing) --------------------------------------------------

    /** Report the current Minecraft server to the relay if it changed. Cheap; safe to call every tick. */
    public void reportServer(boolean force) {
        if (!authed) return;
        String id = currentServerId();
        if (!force && id.equals(lastServer)) return;
        lastServer = id;
        RelayClient c = client;
        if (c == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "server");
        o.addProperty("server", id);
        c.send(o.toString());
    }

    /** The identifier the relay groups "server-only" messages by — the multiplayer address, or singleplayer. */
    private String currentServerId() {
        try {
            ServerData sd = Minecraft.getInstance().getCurrentServer();
            if (sd == null || sd.ip == null || sd.ip.isBlank()) return "singleplayer";
            String ip = sd.ip.strip();
            return ip.length() > 100 ? ip.substring(0, 100) : ip;
        } catch (Throwable t) {
            return "singleplayer";
        }
    }

    /** Per-tick hook (from the addon's onTick): keeps the relay's notion of your current server current. */
    public void tick(Minecraft mc) {
        if (authed) reportServer(false);
    }

    // ---- UI state ------------------------------------------------------------------------------------

    public Mode mode() { return mode; }
    public boolean isAuthed() { return authed; }
    public String status() { return status; }

    /** Cycle OFF → GLOBAL → SERVER → PARTY → OFF (legacy toggle button; the scope-tab bar is the primary UI). */
    public void cycleMode() {
        mode = switch (mode) {
            case OFF -> Mode.GLOBAL;
            case GLOBAL -> Mode.SERVER;
            case SERVER -> Mode.PARTY;
            case PARTY -> Mode.OFF;
            case DM -> Mode.OFF;
        };
    }

    public void setMode(Mode m) { this.mode = m; }

    // ---- scope-tab bar: single source of truth is `mode` (same as the ?bossaddon chat commands) --------

    /** Next mode when a GLOBAL/SERVER/PARTY tab is clicked: activate it, or toggle back OFF if already active. */
    public static Mode nextScope(Mode current, Mode clicked) {
        return current == clicked ? Mode.OFF : clicked;
    }

    /** Next mode when the DM tab is clicked: OFF if already DM; DM if we have a target; otherwise no change. */
    public static Mode nextDmMode(Mode current, boolean hasTarget) {
        if (current == Mode.DM) return Mode.OFF;
        return hasTarget ? Mode.DM : current;
    }

    /** GLOBAL/SERVER/PARTY tab clicked — switch scope (or toggle OFF). Mirrors {@code ?bossaddon chat <scope>}. */
    public void toggleScope(Mode scope) {
        mode = nextScope(mode, scope);
    }

    /** DM tab clicked — activate the DM scope to the remembered target, toggle off, or hint if no target set. */
    public void toggleDmScope() {
        if (mode != Mode.DM && dmTarget == null) {
            display(BossChatFormat.dmNoTarget());   // never a silent no-op
            return;
        }
        mode = nextDmMode(mode, dmTarget != null);
    }

    /** Point the DM scope at a user and activate it (from {@code ?bossaddon chat dm <user>} or the DM tab prompt). */
    public void setDmScope(String toUser) {
        if (toUser != null && !toUser.isBlank()) dmTarget = toUser.trim();
        if (dmTarget != null) {
            mode = Mode.DM;
            display(BossChatFormat.dmScopeSet(dmTarget));
        }
    }

    /**
     * Styled label for the toggle button: a status dot coloured by state, then "BossChat: &lt;mode&gt;".
     * Grey = off, yellow = connecting, green = connected+global, aqua = connected+server. Uses legacy § codes
     * which the vanilla font renders inside the button.
     */
    public String buttonLabel() {
        String color;
        String tag;
        switch (mode) {
            case GLOBAL -> { color = authed ? "§a" : "§e"; tag = authed ? "global" : "global…"; }
            case SERVER -> { color = authed ? "§b" : "§e"; tag = authed ? "server" : "server…"; }
            case PARTY  -> { color = authed ? "§6" : "§e"; tag = authed ? "party" : "party…"; }
            default     -> { color = "§7"; tag = "off"; }
        }
        return color + "● §fBossChat: " + color + tag;
    }

    /** Plain (uncoloured) button text, for width measurement — the longest possible label. */
    public static String buttonLabelWidest() {
        return "● BossChat: global…";
    }

    // ---- display -------------------------------------------------------------------------------------

    private void onInboundMessage(JsonObject o) {
        String scope = str(o, "scope");
        String from = str(o, "from");
        String text = str(o, "text");
        if (from == null || text == null) return;
        boolean fromVerified = bool(o, "verified", true);   // default true for an old server
        // Sanitize again on display — never render raw wire text into chat.
        String safe = RelaySanitizer.sanitize(text);
        if (safe.isEmpty()) return;
        display(BossChatFormat.inbound(scope, from, fromVerified, safe));
    }

    private void echoLocal(String scope, String to, String text) {
        display(BossChatFormat.outbound(scope, to, myVerified, text));
    }

    /** Inbound party control events from the relay: invite prompt, member joins/leaves, roster list. */
    private void onParty(JsonObject o) {
        String event = str(o, "event");
        if (event == null) return;
        switch (event) {
            case "invite" -> display(BossChatFormat.partyInvite(str(o, "from"), bool(o, "fromVerified", true)));
            case "joined" -> display(BossChatFormat.partyJoined(
                str(o, "user"), bool(o, "verified", true), memberCount(o)));
            case "left"   -> display(BossChatFormat.partyLeft(
                str(o, "user"), bool(o, "verified", true), bool(o, "disbanded", false)));
            case "list"   -> {
                JsonArray a = membersArray(o);
                int n = a == null ? 0 : a.size();
                String[] names = new String[n];
                boolean[] verified = new boolean[n];
                for (int i = 0; i < n; i++) {
                    try {
                        JsonObject m = a.get(i).getAsJsonObject();
                        names[i] = str(m, "name");
                        verified[i] = bool(m, "verified", true);
                    } catch (Throwable t) {
                        names[i] = null;
                        verified[i] = true;
                    }
                }
                display(BossChatFormat.partyList(names, verified));
            }
            default -> { /* unknown party event — ignore */ }
        }
    }

    private static JsonArray membersArray(JsonObject o) {
        try {
            return o.has("members") && o.get("members").isJsonArray() ? o.getAsJsonArray("members") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int memberCount(JsonObject o) {
        JsonArray a = membersArray(o);
        return a == null ? 0 : a.size();
    }

    private void display(String s) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(s));
            else System.out.println("[boss-pvp/relay] " + s);
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private void closeQuietly() {
        RelayClient c = client;
        if (c != null) c.close();
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
        } catch (Throwable t) {
            return def;
        }
    }
}
