package com.boss.pvp.relay;

/**
 * Pure, unit-testable construction of the legacy §-code strings BossChat renders into the vanilla chat log.
 * This is the single home of the feature's visual language, so every message type stays consistent — the goal
 * is for BossChat to read as a deliberately designed feature, not a debug print.
 *
 * <p>Design language:
 * <ul>
 *   <li><b>Recognition dot.</b> Every BossChat line — chat AND status — starts with a "●" coloured by
 *       scope/state, so the feature is spottable at a glance in a busy chat log (and it mirrors the dot on
 *       the chat-toggle button).</li>
 *   <li><b>Chat messages</b> lead with a single scope-coloured anchor — "{@code ● [BossChat] }" — then a white
 *       sender name, a grey "{@code :}" separator and a white body. Scope is carried by the anchor COLOUR alone
 *       (aqua global, green server, light-purple DM); it is deliberately not repeated as a "·server"/"·DM"
 *       text tag, so each line reads clean rather than like a stack of coloured fields.</li>
 *   <li><b>System / status lines</b> use a muted, <i>italic</i> "{@code BossChat — …}" form with no bracket and
 *       no "{@code name:}", so a status notice can never be mistaken for a real message at a glance.</li>
 *   <li><b>Unverified</b> senders recede into the background: a dim-grey italic "{@code (unverified)}" marker
 *       AND a grey (not white) name, so they never compete with a verified white name.</li>
 *   <li><b>Boss</b> keeps the relay's server-side bold-black "{@code [Boss]}" prefix (untouched); the client
 *       adds only a subtle gold accent on the name so the owner reads as premium without shouting.</li>
 *   <li><b>DM</b> is visually distinct from global/server: its own light-purple badge plus an explicit
 *       "{@code →}" direction cue (sender → you / you → recipient).</li>
 * </ul>
 *
 * <p><b>Minecraft gotcha handled here:</b> a legacy colour code (§0–§f) RESETS bold/italic, so italic is
 * re-applied after every colour change via {@link #it(String)}. Only glyphs proven to render in the MC font
 * are used: {@code ● · → … —}.
 *
 * <p>Message BODIES are already §-stripped by {@link RelaySanitizer} before they reach here, so this styling
 * is authoritative and cannot be spoofed by user text. Server-side rank/mute logic is untouched — this class
 * is pure client-side presentation.
 */
public final class BossChatFormat {

    private BossChatFormat() {}

    // ---- palette (legacy § codes) — the addon's established chat colours ---------------------------------
    static final String GLOBAL = "§b";   // aqua   — global scope
    static final String SERVER = "§a";   // green  — server scope
    static final String DM     = "§d";   // purple — direct message
    static final String PARTY  = "§6";   // gold   — party scope (a warm colour, distinct from the cool aqua/green/purple)
    static final String GREY   = "§7";   // separators, "you", light scaffolding
    static final String DGREY  = "§8";   // muted markers / status scaffolding
    static final String WHITE  = "§f";   // names + body
    static final String GOLD   = "§6";   // subtle Boss name accent (warm, brand-adjacent)
    static final String WARN   = "§e";   // connecting
    static final String OK     = "§a";   // connected
    static final String ERR    = "§c";   // rejected / error
    static final String RESET  = "§r";
    static final String ITAL   = "§o";

    // ---- glyphs proven to render in the MC font ---------------------------------------------------------
    static final String DOT   = "●";
    static final String MIDDOT = "·";
    static final String ARROW = "→";
    static final String DASH  = "—";
    static final String ELL   = "…";

    /**
     * The exact prefix the relay server prepends for the Boss (see BossRelay ranks.js {@code displayFrom}):
     * bold-black "[Boss] ". Detected so the client can accent the name without altering the prefix. If the
     * server's format ever drifts, {@link #sender} degrades safely to a plain white name.
     */
    static final String BOSS_PREFIX = "§0§l[Boss]§r ";

    /** A colour code with italic re-applied (a colour code alone would clear italic). */
    static String it(String color) { return color + ITAL; }

    private static String norm(String scope) { return scope == null ? "" : scope; }

    /**
     * The one visual anchor a chat line leads with: a scope-coloured "● [BossChat] ". Scope is carried by
     * this colour alone (aqua global / green server / purple DM) — it is deliberately NOT repeated as a
     * "·server"/"·DM" text tag, so each line has a single clean brand anchor rather than a debug-log stack
     * of coloured fields.
     */
    static String badge(String scopeColor) {
        return scopeColor + DOT + " " + scopeColor + "[BossChat] ";
    }

    // ---- chat messages ----------------------------------------------------------------------------------

    /** An inbound message from another user, in the given scope. {@code from} may carry the server Boss prefix. */
    public static String inbound(String scope, String from, boolean fromVerified, String body) {
        String who = sender(from, fromVerified);
        return switch (norm(scope)) {
            case "server" -> badge(SERVER) + who + GREY + ": " + WHITE + body;
            case "party"  -> badge(PARTY) + who + GREY + ": " + WHITE + body;
            case "dm"     -> badge(DM) + who + " " + DGREY + ARROW + " " + GREY + "you" + GREY + ": " + WHITE + body;
            default       -> badge(GLOBAL) + who + GREY + ": " + WHITE + body;
        };
    }

    /** The local echo of a message you just sent (you never receive your own back from the relay). */
    public static String outbound(String scope, String to, boolean meVerified, String body) {
        String me = self(meVerified);
        return switch (norm(scope)) {
            case "server" -> badge(SERVER) + me + GREY + ": " + WHITE + body;
            case "party"  -> badge(PARTY) + me + GREY + ": " + WHITE + body;
            case "dm"     -> badge(DM) + GREY + "you " + DGREY + ARROW + " " + WHITE + (to == null ? "?" : to) + GREY + ": " + WHITE + body;
            default       -> badge(GLOBAL) + me + GREY + ": " + WHITE + body;
        };
    }

    /**
     * Render a sender name with rank/verification markers, never visually identical across trust levels.
     * A verified name is the white anchor; the Boss keeps the server's bold-black "[Boss]" prefix with a
     * gold name; an UNVERIFIED sender fully recedes — a dim-grey italic "(unverified)" marker AND a grey
     * (not white) name — so it fades into the background instead of competing for attention.
     */
    static String sender(String from, boolean verified) {
        String f = from == null ? "" : from;
        if (!verified) {
            return it(DGREY) + "(unverified)" + RESET + " " + GREY + f;   // muted marker + grey name: recedes
        }
        if (f.startsWith(BOSS_PREFIX)) {
            // Keep the server's bold-black [Boss] prefix exactly; accent only the name, in gold.
            return BOSS_PREFIX + GOLD + f.substring(BOSS_PREFIX.length());
        }
        return WHITE + f;   // regular verified user (and any Boss-prefix drift degrades safely to this)
    }

    /** Render "you" for the local echo, marked (and receding) if our own connection is unverified. */
    static String self(boolean verified) {
        return verified
            ? GREY + "you"
            : it(DGREY) + "(unverified)" + RESET + " " + GREY + "you";
    }

    // ---- party events (gold party anchor; member names keep the (unverified) marker) --------------------

    /** Prompt shown to someone who was invited to a party, with the accept/decline hint. */
    public static String partyInvite(String from, boolean fromVerified) {
        return badge(PARTY) + sender(from, fromVerified) + " " + GREY + "invited you to a party " + DGREY + MIDDOT
            + " " + GREY + "?bossaddon party " + WHITE + "accept " + GREY + "or " + WHITE + "decline";
    }

    // ---- party warp (consent-gated: a request, never an automatic connect) ------------------------------

    /**
     * Prompt shown to a party member who received a warp request. The exact destination address is shown in
     * full so the recipient can see where they'd be sent BEFORE choosing accept/decline — nothing connects
     * until they type accept.
     */
    public static String warpRequest(String from, boolean fromVerified, String address) {
        return badge(PARTY) + sender(from, fromVerified) + " " + GREY + "wants you to join them on "
            + WHITE + (address == null ? "?" : address) + " " + DGREY + MIDDOT + " "
            + GREY + "?bossaddon party warp " + WHITE + "accept " + GREY + "or " + WHITE + "decline";
    }

    /** Confirmation to the sender that a warp request went out. {@code target} is a name or "your party". */
    public static String warpSent(String target) {
        return status(OK, it(OK) + "warp request sent " + it(DGREY) + "to " + it(WHITE)
            + (target == null ? "your party" : target));
    }

    /** You declined (or cleared) the incoming warp request. */
    public static String warpDeclined() {
        return status(GREY, it(GREY) + "warp request declined");
    }

    /** A member declined the warp you proposed. */
    public static String warpDeclinedBy(String user, boolean verified) {
        return badge(PARTY) + sender(user, verified) + " " + GREY + "declined your warp request";
    }

    /** There is no pending warp request to accept or decline. */
    public static String warpNonePending() {
        return status(GREY, it(GREY) + "no warp request to respond to");
    }

    /** You tried to propose a warp but aren't on a server you can send people to. */
    public static String warpNotOnServer() {
        return status(GREY, it(GREY) + "you're not on a server you can warp people to");
    }

    /** Accepted — now connecting to the destination the request named. */
    public static String warpConnecting(String address) {
        return status(WARN, it(WARN) + "joining " + it(WHITE) + (address == null ? "?" : address) + it(GREY) + ELL);
    }

    /** A member joined the party. */
    public static String partyJoined(String user, boolean verified, int memberCount) {
        return badge(PARTY) + sender(user, verified) + " " + GREY + "joined the party " + DGREY + "(" + GREY
            + memberCount + DGREY + ")";
    }

    /** A member left, or the party disbanded (dropped below two members). */
    public static String partyLeft(String user, boolean verified, boolean disbanded) {
        return disbanded
            ? badge(PARTY) + GREY + "the party disbanded"
            : badge(PARTY) + sender(user, verified) + " " + GREY + "left the party";
    }

    /** The current party roster; each member keeps its (unverified) marker. */
    public static String partyList(String[] names, boolean[] verified) {
        int n = names == null ? 0 : names.length;
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) members.append(GREY).append(", ");
            boolean v = verified != null && i < verified.length && verified[i];
            members.append(sender(names[i], v));
        }
        return badge(PARTY) + GREY + "party " + DGREY + "(" + GREY + n + DGREY + ")" + GREY + ": " + members;
    }

    // ---- system / status lines (muted italic; structurally distinct from chat) ---------------------------

    /**
     * The shared status form: a state-coloured dot, then a muted italic "BossChat — {body}". {@code body}
     * supplies its own colour(s) with italic re-applied per segment (use {@link #it}).
     */
    static String status(String dotColor, String body) {
        return dotColor + DOT + " " + it(DGREY) + "BossChat " + it(DGREY) + DASH + " " + body;
    }

    public static String connecting() {
        return status(WARN, it(GREY) + "connecting" + ELL);
    }

    public static String connectingUnverified() {
        return status(WARN, it(GREY) + "connecting as " + it(DGREY) + "(unverified)" + it(GREY) + ELL);
    }

    public static String connectedVerified() {
        return status(OK, it(OK) + "connected " + it(DGREY) + "(verified) " + it(GREY) + MIDDOT
            + " type in chat or " + it(WHITE) + "?bossaddon chat");
    }

    public static String connectedUnverified() {
        return status(OK, it(OK) + "connected " + it(DGREY) + "as (unverified) " + it(GREY) + MIDDOT
            + " others see you marked");
    }

    public static String disconnected() {
        return status(GREY, it(GREY) + "disconnected");
    }

    // ---- cold start (the relay's host was asleep and is waking up) --------------------------------------
    //
    // This is a routine, self-healing state, not an error: the free-tier host spins the service down after
    // 15 minutes without inbound HTTP, so the first connection after a quiet spell always pays for the
    // wake-up. It gets its own message set so it never reads like an outage — the user is told what is
    // happening, roughly how long it takes, and sees progress rather than silence.

    /**
     * First notice that the relay is spinning up, with the expected wait. The estimate is a RANGE on purpose:
     * the host's own documentation says a spin-up "takes about one minute", while this relay is a small Node
     * service that has been observed back in ~12s. A single number would be wrong in one direction or the other.
     */
    public static String coldStart(int lowSeconds, int highSeconds) {
        return status(WARN, it(WARN) + "BossRelay is starting up " + it(DGREY) + MIDDOT + " "
            + it(GREY) + "estimated " + it(WHITE) + "~" + lowSeconds + DASH + highSeconds + "s"
            + it(GREY) + " (the server sleeps when idle)" + ELL);
    }

    /** Periodic progress while waiting, so the wait is never silent. */
    public static String coldStartProgress(int elapsedSeconds, int attempt) {
        return status(WARN, it(GREY) + "still starting " + it(DGREY) + MIDDOT + " " + it(WHITE)
            + elapsedSeconds + "s" + it(GREY) + " elapsed, check " + it(WHITE) + attempt + it(GREY) + ELL);
    }

    /** The health endpoint answered — the service is up and we're reconnecting. */
    public static String coldStartReady(int elapsedSeconds) {
        return status(OK, it(OK) + "BossRelay is awake " + it(DGREY) + "(" + elapsedSeconds + "s)" + " "
            + it(GREY) + MIDDOT + " connecting" + ELL);
    }

    /** Gave up waiting for the wake-up; falling back to ordinary retries. */
    public static String coldStartTimedOut(int elapsedSeconds) {
        return status(ERR, it(ERR) + "BossRelay didn't come up within " + it(WHITE) + elapsedSeconds + "s"
            + " " + it(DGREY) + MIDDOT + " " + it(GREY) + "retrying in the background");
    }

    /** Nothing is answering at all — distinct from a spin-up, so it must not claim the server is "starting". */
    public static String unreachable() {
        return status(ERR, it(ERR) + "can't reach BossRelay " + it(DGREY) + MIDDOT + " "
            + it(GREY) + "check your connection; retrying in the background");
    }

    public static String rejected(String reason) {
        return status(ERR, it(ERR) + "rejected" + it(DGREY) + ": " + it(GREY) + (reason == null ? "unknown" : reason));
    }

    /** Verified-only relay refused an offline/unverified attempt ({@code forced} = user forced offline mode). */
    public static String verifiedOnly(boolean forced) {
        return status(ERR, it(ERR) + (forced
            ? "this relay is verified-only " + it(DGREY) + DASH + it(GREY) + " offline accounts aren't allowed"
            : "Mojang auth failed " + it(DGREY) + DASH + it(GREY) + " this relay is verified-only"));
    }

    public static String notConnected() {
        return status(ERR, it(ERR) + "not connected");
    }

    /** A generic system notice relayed from the server (already §-sanitized by the caller). */
    public static String system(String text) {
        return status(GREY, it(GREY) + (text == null ? "" : text));
    }

    // ---- ?bossaddon chat command feedback (same status form, so the command feels part of the feature) ---

    /** Response to a scope change: green when a scope is active, grey when turned off. */
    public static String scopeChanged(String modeName, boolean off) {
        String c = off ? GREY : OK;
        return status(c, it(c) + "scope " + it(DGREY) + "= " + it(WHITE) + modeName + " " + it(GREY) + MIDDOT + " "
            + it(GREY) + (off ? "chat is normal" : "typed chat goes to BossChat"));
    }

    /** Response to a manual ?bossaddon chat reconnect. */
    public static String reconnecting() {
        return status(WARN, it(GREY) + "reconnecting" + ELL);
    }

    /** Response to ?bossaddon chat status: current connection state and active scope. */
    public static String statusReport(String connStatus, String scope) {
        return status(GREY, it(GREY) + "status " + it(DGREY) + "= " + it(WHITE) + (connStatus == null ? "?" : connStatus)
            + " " + it(GREY) + MIDDOT + " scope " + it(DGREY) + "= " + it(WHITE) + (scope == null ? "?" : scope));
    }

    /** Shown when the command runs on an install with no relay URL configured (BossChat is inert). */
    public static String notEnabled() {
        return status(GREY, it(GREY) + "not enabled on this install " + it(DGREY) + "(no relay URL configured)");
    }

    /** Shown when the user fully opts out with ?bossaddon chat disable. */
    public static String disabled() {
        return status(GREY, it(GREY) + "BossChat disabled " + it(DGREY) + "(?bossaddon chat enable turns it back on)");
    }

    /** The DM scope was clicked/typed with no target remembered yet — tell the user how to set one. */
    public static String dmNoTarget() {
        return status(GREY, it(GREY) + "no DM target yet " + it(DGREY) + MIDDOT + " "
            + it(GREY) + "set one with " + it(WHITE) + "?bossaddon chat dm " + it(GREY) + "<user> [message]");
    }

    /** Confirmation that the DM scope is now pointed at {@code user}. */
    public static String dmScopeSet(String user) {
        return status(DM, it(DM) + "DM scope on " + it(DGREY) + MIDDOT + " "
            + it(GREY) + "your chat now goes to " + it(WHITE) + (user == null ? "?" : user));
    }
}
