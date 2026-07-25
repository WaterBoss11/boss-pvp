package com.boss.pvp;

import com.boss.pvp.relay.RelayConfig;
import com.boss.pvp.relay.RelayManager;
import com.boss.pvp.util.AddonHalves;
import com.boss.pvp.util.MenuMode;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;

/**
 * The single command entry point for the whole addon: {@code ?bossaddon} and its subcommands. Everything is
 * reached through this one root, so a user types {@code ?bossaddon help} and can discover every feature.
 *
 * <pre>
 *   ?bossaddon                      status overview
 *   ?bossaddon help [topic]         list commands, or details on one
 *   ?bossaddon pvp on|off           toggle the PvP half
 *   ?bossaddon utility on|off       toggle the Utility half
 *   ?bossaddon menu simple|advanced settings menu detail level
 *   ?bossaddon chat ...             BossChat controls (scopes, messaging, DMs, enable/disable)
 *   ?bossaddon party ...            BossChat party (membership, invite, party chat, warp)
 * </pre>
 *
 * <p><b>Prefix.</b> The addon deliberately uses {@code "?"} rather than {@code "/"}. {@code "/"} is a real
 * Minecraft command character sent to the server; {@code "?"} is not, so it can't reach the server and won't
 * collide with server commands, AUTISM's command prefix (one of {@code . % - _ * # @ & =}), or its panic
 * fallbacks ({@code . , ; : - # ! ~}). Because {@code "?"} would otherwise be ordinary chat, the commands are
 * NOT registered with Fabric's {@code "/"} dispatcher — they live in the private {@link #DISPATCHER} below.
 *
 * <p><b>{@code bossaddon} is optional.</b> {@code "?"} == {@code "?bossaddon"}, {@code "? help"} ==
 * {@code "?bossaddon help"}, {@code "? pvp on"} == {@code "?bossaddon pvp on"}, etc. (see {@link #canonical}) —
 * the leading {@code bossaddon} token is prepended automatically when absent, so both forms hit the same tree.
 *
 * <p><b>Interception rule.</b> {@code ChatScreenMixin} intercepts <i>any</i> line whose first character is
 * {@code "?"} (see {@link #shouldIntercept}) and hands it to {@link #dispatch}, which is cancelled before the
 * line can be sent as chat. If it's a recognized command it runs; otherwise the sender gets a local-only
 * "unknown command" notice. So <b>nothing beginning with {@code "?"} ever reaches the server or other
 * players</b> — a {@code "?"} line can never be used as ordinary chat.
 */
public final class BossAddonInit implements ClientModInitializer {

    /** The command prefix. Not "/" — see the class javadoc. ANY line starting with this is intercepted. */
    public static final String PREFIX = "?";
    /** The single root literal; {@code ?bossaddon ...} is the recognized command form. */
    public static final String ROOT = "bossaddon";

    // Private Brigadier dispatcher (the tree is fed "bossaddon ..." strings, without the "?" prefix). The
    // source is unused — every handler ignores it — so a throwaway Object is fine.
    private static final CommandDispatcher<Object> DISPATCHER = new CommandDispatcher<>();
    private static final Object SOURCE = new Object();

    // A second, FLAT tree used only to drive the native vanilla command-suggestion dropdown for "?" input.
    // Here the subcommands are registered at the TOP level (not under a "bossaddon" root), so parsing the text
    // from the point where the subcommand begins produces Brigadier suggestions whose character ranges line up
    // with the real chat-input string — which is what lets us hand them to vanilla's own CommandSuggestions
    // widget (see BossCommandSuggestionsMixin). Both prefix forms are served from this one tree: the short
    // "?<sub>" form parses from just after "?", and the long "?bossaddon <sub>" form parses from just after
    // "?bossaddon " (see parseSuggest / suggestContentStart), so each gets an aligned dropdown without a separate
    // tree. Recognition/execution still go through DISPATCHER + canonical() above; this tree is suggestion-only.
    private static final CommandDispatcher<Object> SUGGEST = new CommandDispatcher<>();

    /** One row of the help listing: the subcommand and a short, plain-language description (5-8 words). */
    private record Cmd(String usage, String desc) {}

    // Top-level subcommands, in the order shown by ?bossaddon help.
    private static final Cmd[] HELP = {
        new Cmd("help [topic]",        "List commands, or details on one"),
        new Cmd("pvp on|off",          "Turn the PvP half on or off"),
        new Cmd("utility on|off",      "Turn the Utility half on or off"),
        new Cmd("menu simple|advanced","Choose how much the menu shows"),
        new Cmd("chat ...",            "BossChat: talk across servers"),
        new Cmd("party ...",           "Make and manage a chat party"),
    };

    // The command tree is built at class-init (not in onInitializeClient) so it's ready for both the ChatScreen
    // mixin and unit tests, without needing a running client. Building only creates Brigadier nodes — the
    // handler lambdas are not invoked here, so nothing touches Minecraft.
    static {
        DISPATCHER.register(lit(ROOT)
            .then(half(AddonHalves.PVP))
            .then(half(AddonHalves.UTILITY))
            .then(menuSubtree("menu"))
            .then(chatSubtree("chat"))
            .then(partySubtree("party"))
            .then(helpSubtree("help"))
            .executes(ctx -> overview()));

        // The flat suggestion tree: the same subcommands, registered at the top level (fresh builders — the
        // helpers construct a new node graph each call). Drives the native "?" dropdown only.
        SUGGEST.register(half(AddonHalves.PVP));
        SUGGEST.register(half(AddonHalves.UTILITY));
        SUGGEST.register(menuSubtree("menu"));
        SUGGEST.register(chatSubtree("chat"));
        SUGGEST.register(partySubtree("party"));
        SUGGEST.register(helpSubtree("help"));
    }

    @Override
    public void onInitializeClient() {
        // Nothing to do: the command dispatcher is built in the static initializer above.
    }

    // ---- ? interception entry points (called from ChatScreenMixin) -----------------------------------

    /**
     * True when a chat line must be intercepted — i.e. its first character is the {@code "?"} prefix. EVERY
     * such line is handled locally by {@link #dispatch} and cancelled before it can be sent: a recognized
     * {@code ?bossaddon} command runs, anything else gets a local "unknown command" notice. Nothing beginning
     * with {@code "?"} is ever sent to the server or other players, so {@code "?"} can't start ordinary chat.
     */
    public static boolean shouldIntercept(String message) {
        return message != null && message.startsWith(PREFIX);
    }

    /**
     * Canonicalize an intercepted line (already stripped of the leading {@code "?"}) into the form the Brigadier
     * tree expects, making {@code "bossaddon"} OPTIONAL. Empty input becomes the bare root; a line that already
     * starts with the {@code bossaddon} token is left as-is; anything else has {@code "bossaddon "} prepended.
     * So {@code "?"} == {@code "?bossaddon"}, {@code "? help"} == {@code "?bossaddon help"}, {@code "? pvp on"}
     * == {@code "?bossaddon pvp on"}, etc. Pure/testable.
     */
    public static String canonical(String withoutPrefix) {
        String s = withoutPrefix == null ? "" : withoutPrefix.trim();
        if (s.isEmpty()) return ROOT;                                   // "?" -> "bossaddon" (overview)
        if (s.equals(ROOT) || s.startsWith(ROOT + " ")) return s;       // already "bossaddon ..."
        return ROOT + " " + s;                                          // "? help" -> "bossaddon help"
    }

    /**
     * True if {@code withoutPrefix} (the line minus the leading {@code "?"}) resolves to a complete, recognized
     * command — with {@code bossaddon} optional (see {@link #canonical}). Uses a dry {@code parse} (no
     * execution), so it is pure and touches no client state — the recognized/unrecognized split {@link #dispatch}
     * acts on.
     */
    public static boolean isRecognizedCommand(String withoutPrefix) {
        String input = canonical(withoutPrefix);
        com.mojang.brigadier.ParseResults<Object> parse = DISPATCHER.parse(input, SOURCE);
        // Recognized iff the whole line was consumed (no trailing/unmatched text) AND parsing reached an
        // executable node. NOTE: we do NOT check getExceptions() — Brigadier records the failures of sibling
        // branches that didn't match even for a perfectly valid command, so a non-empty map is normal.
        if (parse.getReader().canRead()) return false;         // trailing input that didn't match anything
        com.mojang.brigadier.context.CommandContextBuilder<Object> ctx = parse.getContext();
        while (ctx != null) {
            if (ctx.getCommand() != null) return true;         // reached an executable node
            ctx = ctx.getChild();
        }
        return false;
    }

    /**
     * Tab-complete candidates for a partial line ({@code "?"} already stripped), with {@code bossaddon} optional.
     * Returns the next-token completion strings from the Brigadier tree — e.g. {@code ""} &rarr; the subcommand
     * names, {@code "p"} &rarr; {@code [party, pvp]}, {@code "pvp "} &rarr; {@code [off, on]}, {@code "menu "}
     * &rarr; {@code [advanced, simple]}, an unmatched token &rarr; empty. Pure/testable: our dispatcher has no
     * async suggestion providers, so {@code getCompletionSuggestions} completes immediately.
     */
    public static java.util.List<String> suggest(String withoutPrefix) {
        String core = withoutPrefix == null ? "" : withoutPrefix;
        if (core.equals(ROOT)) core = "";
        else if (core.startsWith(ROOT + " ")) core = core.substring(ROOT.length() + 1);
        // Always "bossaddon <core>" with the trailing text driving the completion (a trailing space asks for
        // the next subcommand; a partial token asks to complete it).
        String canon = ROOT + " " + core;
        com.mojang.brigadier.ParseResults<Object> parse = DISPATCHER.parse(canon, SOURCE);
        com.mojang.brigadier.suggestion.Suggestions s = DISPATCHER.getCompletionSuggestions(parse).join();
        java.util.List<String> out = new java.util.ArrayList<>(s.getList().size());
        for (com.mojang.brigadier.suggestion.Suggestion sug : s.getList()) out.add(sug.getText());
        return out;
    }

    /**
     * Parse a full {@code "?..."} chat line (the {@code "?"} still attached) against the flat {@link #SUGGEST}
     * tree, starting the reader at the point where the completable subcommand text begins — see
     * {@link #suggestContentStart}. Because the reader keeps its absolute position, the Brigadier suggestions it
     * produces carry character ranges that line up with the real chat-input string, for <b>both</b> the short
     * {@code "?<sub>"} form and the long {@code "?bossaddon <sub>"} form (only the start offset differs; the
     * subcommand/argument data is the same flat tree). So they can be fed straight to vanilla's
     * {@code CommandSuggestions} widget. Suggestion-only; recognition and execution use {@link #DISPATCHER} via
     * {@link #canonical}.
     */
    public static com.mojang.brigadier.ParseResults<Object> parseSuggest(String fullValue) {
        String v = fullValue == null ? "" : fullValue;
        com.mojang.brigadier.StringReader reader = new com.mojang.brigadier.StringReader(v);
        reader.setCursor(Math.min(suggestContentStart(v), v.length()));
        return SUGGEST.parse(reader, SOURCE);
    }

    /**
     * The index in a {@code "?..."} line where the completable subcommand text starts, i.e. how far to advance the
     * suggestion reader so its ranges land in the real input. For the long form {@code "?bossaddon <sub>"} that is
     * just past {@code "?bossaddon "}; otherwise (the short {@code "?<sub>"} form) it is just past the {@code "?"}.
     * Both then parse the same flat {@link #SUGGEST} tree, so the completions match while the ranges stay aligned.
     */
    private static int suggestContentStart(String fullValue) {
        String longPrefix = PREFIX + ROOT + " ";                // "?bossaddon "
        if (fullValue.startsWith(longPrefix)) return longPrefix.length();
        return PREFIX.length();                                  // "?"
    }

    /**
     * Native-dropdown completions for a full {@code "?..."} line at the given cursor. The returned suggestions'
     * ranges are absolute positions in {@code fullValue} (e.g. {@code "?p"} at cursor 2 &rarr; {@code party}/
     * {@code pvp}, each replacing {@code [1,2]}, so {@code apply} yields {@code "?party"}/{@code "?pvp"}). Pure and
     * client-free (Brigadier only), so it is unit-testable; completes immediately (no async providers).
     */
    public static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestionsFor(String fullValue, int cursor) {
        return SUGGEST.getCompletionSuggestions(parseSuggest(fullValue), cursor);
    }

    /**
     * Native-dropdown completions for a full {@code "?..."} line, completing at the END of the line (the whole
     * final token). This is what the live dropdown uses: completing at the raw caret position would splice a
     * suggestion into the middle of a token when the caret isn't at the end and duplicate the trailing characters
     * (e.g. {@code "?pvp o"} with the caret before a stray char &rarr; {@code "?pvp offo"}). End-of-input
     * completion always replaces the whole final token cleanly.
     */
    public static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestionsFor(String fullValue) {
        return suggestionsFor(fullValue, fullValue == null ? 0 : fullValue.length());
    }

    /**
     * Handle an intercepted line, given WITHOUT the leading {@code "?"}. A recognized command runs (with
     * {@code bossaddon} optional); anything else shows a local-only "unknown command" notice. Never sends
     * anything to the server/chat.
     */
    public static void dispatch(String withoutPrefix) {
        String input = canonical(withoutPrefix);
        if (!isRecognizedCommand(withoutPrefix)) {
            msg("§7Unknown command. Try §f?help§7.");
            return;
        }
        try {
            DISPATCHER.execute(input, SOURCE);
        } catch (Throwable t) {
            msg("§7Command error: " + t.getMessage());
        }
    }

    // ---- brigadier builder shorthands (source type is unused Object) ---------------------------------

    private static LiteralArgumentBuilder<Object> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static RequiredArgumentBuilder<Object, String> strArg(String name, ArgumentType<String> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    // ---- halves --------------------------------------------------------------------------------------

    private static LiteralArgumentBuilder<Object> half(String name) {
        return lit(name)
            .then(lit("on").executes(ctx -> applyHalf(name, true)))
            .then(lit("off").executes(ctx -> applyHalf(name, false)))
            .executes(ctx -> { msg("[BossAddon] " + AddonHalves.status()); return 1; });
    }

    private static int applyHalf(String half, boolean on) {
        String result = AddonHalves.setHalf(half, on);
        msg("[BossAddon] " + (result == null ? "Unknown half: " + half : result));
        return 1;
    }

    // ---- menu mode -----------------------------------------------------------------------------------

    private static LiteralArgumentBuilder<Object> menuSubtree(String name) {
        return lit(name)
            .then(lit("simple").executes(ctx -> setMenuMode(false)))
            .then(lit("advanced").executes(ctx -> setMenuMode(true)))
            .executes(ctx -> setMenuMode(!MenuMode.advanced()));
    }

    private static int setMenuMode(boolean advanced) {
        MenuMode.setAdvanced(advanced);
        msg("[Boss's PVP] Menu mode: "
            + (advanced ? "Advanced (all settings shown)" : "Simple (essential settings only)"));
        return 1;
    }

    // ---- BossChat / party subtrees -------------------------------------------------------------------

    /** ?bossaddon chat: scope toggles, messaging, DMs, reconnect, enable/disable, bare = status. */
    private static LiteralArgumentBuilder<Object> chatSubtree(String name) {
        return lit(name)
            .then(lit("off").executes(ctx -> setRelayMode(RelayManager.Mode.OFF)))
            .then(lit("global").executes(ctx -> setRelayMode(RelayManager.Mode.GLOBAL)))
            .then(lit("server").executes(ctx -> setRelayMode(RelayManager.Mode.SERVER)))
            .then(lit("reconnect").executes(ctx -> relayReconnect()))
            // Full opt-out (distinct from the OFF scope: OFF still receives; disable disconnects entirely).
            // These deliberately bypass relayGate() so "enable" works while the feature is currently disabled.
            .then(lit("disable").executes(ctx -> relayDisable()))
            .then(lit("enable").executes(ctx -> relayEnable()))
            .then(lit("g")
                .then(strArg("message", StringArgumentType.greedyString())
                    .executes(ctx -> relaySend("global", null, StringArgumentType.getString(ctx, "message")))))
            .then(lit("s")
                .then(strArg("message", StringArgumentType.greedyString())
                    .executes(ctx -> relaySend("server", null, StringArgumentType.getString(ctx, "message")))))
            .then(lit("dm")
                .then(strArg("user", StringArgumentType.word())
                    // "dm <user>" (no message): point the DM send-scope at them (same as clicking the DM tab).
                    .executes(ctx -> relaySetDmScope(StringArgumentType.getString(ctx, "user")))
                    // "dm <user> <message>": one-shot DM (also remembers them as the DM target).
                    .then(strArg("message", StringArgumentType.greedyString())
                        .executes(ctx -> relaySend("dm", StringArgumentType.getString(ctx, "user"),
                            StringArgumentType.getString(ctx, "message"))))))
            .executes(ctx -> relayStatus());
    }

    /** ?bossaddon party: membership actions, invite by name, "msg" for party chat, warp, bare = list. */
    private static LiteralArgumentBuilder<Object> partySubtree(String name) {
        return lit(name)
            .then(lit("accept").executes(ctx -> relayParty("accept")))
            .then(lit("decline").executes(ctx -> relayParty("decline")))
            .then(lit("leave").executes(ctx -> relayParty("leave")))
            .then(lit("list").executes(ctx -> relayParty("list")))
            // Explicit, clearly-named invite: "party invite <user>". Same handler as the bare "party <user>"
            // shortcut below, which is kept as an additive alias so neither form breaks.
            .then(lit("invite")
                .then(strArg("user", StringArgumentType.word())
                    .executes(ctx -> relayPartyInvite(StringArgumentType.getString(ctx, "user")))))
            .then(lit("msg")
                .then(strArg("message", StringArgumentType.greedyString())
                    .executes(ctx -> relaySend("party", null, StringArgumentType.getString(ctx, "message")))))
            // Warp: propose your current server to a member (or the whole party); the recipient must accept.
            // "warp" bare = propose to the party; "warp <user>" = propose to one member; accept/decline respond.
            .then(lit("warp")
                .then(lit("accept").executes(ctx -> relayWarpAccept()))
                .then(lit("decline").executes(ctx -> relayWarpDecline()))
                .then(strArg("user", StringArgumentType.word())
                    .executes(ctx -> relayWarpPropose(StringArgumentType.getString(ctx, "user"))))
                .executes(ctx -> relayWarpPropose(null)))
            // (Brigadier matches the literals above before the <user> argument, so a user literally named e.g.
            // "list" can't be invited by name — an acceptable v1 edge case.)
            .then(strArg("user", StringArgumentType.word())
                .executes(ctx -> relayPartyInvite(StringArgumentType.getString(ctx, "user"))))
            .executes(ctx -> relayParty("list"));
    }

    // ---- relay handlers ------------------------------------------------------------------------------

    private static int setRelayMode(RelayManager.Mode mode) {
        if (relayGate()) return 1;
        RelayManager.get().setMode(mode);
        msg(com.boss.pvp.relay.BossChatFormat.scopeChanged(
            mode.name().toLowerCase(), mode == RelayManager.Mode.OFF));
        return 1;
    }

    private static int relaySend(String scope, String user, String message) {
        if (relayGate()) return 1;
        switch (scope) {
            case "server" -> RelayManager.get().sendServer(message);
            case "party" -> RelayManager.get().sendParty(message);
            case "dm" -> RelayManager.get().sendDm(user, message);
            default -> RelayManager.get().sendGlobal(message);
        }
        return 1;
    }

    private static int relayParty(String action) {
        if (relayGate()) return 1;
        RelayManager r = RelayManager.get();
        // A pending WARP is answered by "party warp accept", not "party accept". Since the relay holds no
        // warp state, answering the wrong one gets "You have no pending party invite" — a message about a
        // different feature, which reads as the warp being broken. Point at the right command instead.
        // Only a hint, never a redirect: accepting a warp moves you to another server, so it stays explicit.
        if (("accept".equals(action) || "decline".equals(action)) && r.hasPendingWarp()) {
            msg(com.boss.pvp.relay.BossChatFormat.warpPendingHint(action));
        }
        switch (action) {
            case "accept" -> r.partyAccept();
            case "decline" -> r.partyDecline();
            case "leave" -> r.partyLeave();
            case "list" -> r.partyList();
        }
        return 1;
    }

    private static int relayPartyInvite(String user) {
        if (relayGate()) return 1;
        RelayManager.get().partyInvite(user);
        return 1;
    }

    /** Point the DM send-scope at a user (the DM tab / "?bossaddon chat dm <user>"). */
    private static int relaySetDmScope(String user) {
        if (relayGate()) return 1;
        RelayManager.get().setDmScope(user);
        return 1;
    }

    /** Propose a warp to your current server: to one member (user != null) or the whole party (null). */
    private static int relayWarpPropose(String user) {
        if (relayGate()) return 1;
        RelayManager.get().warpPropose(user);
        return 1;
    }

    private static int relayWarpAccept() {
        if (relayGate()) return 1;
        RelayManager.get().warpAccept();
        return 1;
    }

    private static int relayWarpDecline() {
        if (relayGate()) return 1;
        RelayManager.get().warpDecline();
        return 1;
    }

    private static int relayReconnect() {
        if (relayGate()) return 1;
        msg(com.boss.pvp.relay.BossChatFormat.reconnecting());
        RelayManager.get().connect();
        return 1;
    }

    private static int relayStatus() {
        if (relayGate()) return 1;
        RelayManager r = RelayManager.get();
        msg(com.boss.pvp.relay.BossChatFormat.statusReport(r.status(), r.mode().name().toLowerCase()));
        return 1;
    }

    /** Full opt-out: persist the flag first (so the gate is off), then tear the connection down. */
    private static int relayDisable() {
        RelayConfig.setEnabled(false);
        RelayManager.get().disconnect();
        msg(com.boss.pvp.relay.BossChatFormat.disabled());
        return 1;
    }

    /** Opt back in: persist enabled, then connect (only meaningful if a relay URL is configured). */
    private static int relayEnable() {
        RelayConfig.setEnabled(true);
        if (RelayConfig.url() == null) {
            msg(com.boss.pvp.relay.BossChatFormat.notEnabled());
            return 1;
        }
        msg(com.boss.pvp.relay.BossChatFormat.reconnecting());
        RelayManager.get().connect();
        return 1;
    }

    /** True (and prints a hint) when the relay isn't configured — BossChat is inert on this install. */
    private static boolean relayGate() {
        if (RelayConfig.isConfigured()) return false;
        msg(com.boss.pvp.relay.BossChatFormat.notEnabled());
        return true;
    }

    // ---- help ----------------------------------------------------------------------------------------

    private static LiteralArgumentBuilder<Object> helpSubtree(String name) {
        return lit(name)
            .then(strArg("topic", StringArgumentType.word())
                .executes(ctx -> helpTopic(StringArgumentType.getString(ctx, "topic"))))
            .executes(ctx -> helpList());
    }

    /** Bare ?bossaddon: halves status + a pointer to help. */
    private static int overview() {
        msg("§6[BossAddon]§r " + AddonHalves.status());
        msg("§7Type §f?help§7 to see every command §8(\"bossaddon\" is optional: §f?help§8 = §f?bossaddon help§8).");
        return 1;
    }

    /** ?bossaddon help: one line per subcommand. */
    private static int helpList() {
        msg("§6[BossAddon] Commands§r §7(?help <topic> for detail §8· \"bossaddon\" optional)");
        for (Cmd c : HELP) {
            msg("  §f?" + c.usage() + "§8 — §7" + c.desc());
        }
        return 1;
    }

    /** ?bossaddon help <topic>: a few lines of detail for one subcommand. */
    private static int helpTopic(String topic) {
        switch (topic.toLowerCase()) {
            case "pvp" -> {
                msg("§6?pvp on|off§r");
                msg("§7Turns every PvP module on or off at once.");
                msg("§7Off = modules disabled and their ticking skipped.");
                msg("§7Turning it back on restores what was enabled.");
            }
            case "utility" -> {
                msg("§6?utility on|off§r");
                msg("§7Turns every Utility module on or off at once.");
                msg("§7Off = modules disabled and their ticking skipped.");
                msg("§7Turning it back on restores what was enabled.");
            }
            case "menu" -> {
                msg("§6?menu simple|advanced§r");
                msg("§7Simple shows essential settings only.");
                msg("§7Advanced shows every setting.");
                msg("§7No argument flips between the two.");
            }
            case "chat" -> {
                msg("§6?chat§r §7(talk across servers)");
                msg("  §fglobal§8 — §7Relay your chat to everyone");
                msg("  §fserver§8 — §7Relay to this server only");
                msg("  §foff§8 — §7Stop relaying, still receive");
                msg("  §fg §7<msg>§8 — §7Send one line to global");
                msg("  §fs §7<msg>§8 — §7Send one line to server");
                msg("  §fdm §7<user> <msg>§8 — §7Private message someone");
                msg("  §fdm §7<user>§8 — §7Aim your chat at them (DM scope/tab)");
                msg("  §freconnect§8 — §7Reconnect to the relay");
                msg("  §fdisable§8 / §fenable§8 — §7Turn BossChat fully off/on");
            }
            case "party" -> {
                msg("§6?party§r §7(private group chat)");
                msg("  §finvite §7<user>§8 — §7Invite a player to your party");
                msg("  §f<user>§8 — §7Shortcut for §finvite <user>");
                msg("  §faccept§8 / §fdecline§8 — §7Answer an invite");
                msg("  §fleave§8 — §7Leave your current party");
                msg("  §flist§8 — §7Show who is in the party");
                msg("  §fmsg §7<msg>§8 — §7Send one line to the party");
                msg("  §fwarp §7[user]§8 — §7Ask them to join your server");
                msg("  §fwarp accept§8 / §fwarp decline§8 — §7Answer a warp");
            }
            default -> {
                msg("§7No help for §f" + topic + "§7. Try one of:");
                msg("§7  pvp, utility, menu, chat, party");
            }
        }
        return 1;
    }

    private static void msg(String s) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(s));
        }
    }
}
