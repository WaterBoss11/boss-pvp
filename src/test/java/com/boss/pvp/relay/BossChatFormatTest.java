package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BossChat visual language, tested as pure string construction (no client needed). Locks in the per-scope
 * badges, the rank/verification markers, the distinct system-line style, and — critically — the Minecraft rule
 * that a colour code clears italic, so italic must be re-applied after every colour.
 */
class BossChatFormatTest {

    private static final String BOSS = BossChatFormat.BOSS_PREFIX; // "§0§l[Boss]§r "

    // ---- recognition dot: EVERY line leads with a coloured ● ----------------------------------------

    @Test
    void everyLineStartsWithAColouredRecognitionDot() {
        String[] lines = {
            BossChatFormat.inbound("global", "A", true, "x"),
            BossChatFormat.inbound("server", "A", true, "x"),
            BossChatFormat.inbound("dm", "A", true, "x"),
            BossChatFormat.outbound("global", null, true, "x"),
            BossChatFormat.connecting(),
            BossChatFormat.connectedVerified(),
            BossChatFormat.rejected("nope"),
            BossChatFormat.system("hi"),
        };
        for (String s : lines) {
            // "§x●" — a colour code immediately followed by the dot glyph.
            assertTrue(s.matches("§.●.*"), "line must start with a coloured dot: " + s);
            assertTrue(s.contains("●"), "line must contain the recognition dot: " + s);
        }
    }

    // ---- chat: per-scope badge, colour and structure ------------------------------------------------

    @Test
    void inboundGlobalUsesAquaBossChatBadge() {
        assertEquals("§b● §b[BossChat] §fAlice§7: §fhi",
            BossChatFormat.inbound("global", "Alice", true, "hi"));
    }

    @Test
    void inboundServerUsesGreenBadgeWithScopeByColourOnly() {
        // Same [BossChat] anchor as global — scope is the GREEN colour, not a "·server" text tag.
        assertEquals("§a● §a[BossChat] §fBob§7: §fyo",
            BossChatFormat.inbound("server", "Bob", true, "yo"));
    }

    @Test
    void inboundDmUsesPurpleBadgeAndDirectionArrow() {
        String line = BossChatFormat.inbound("dm", "Carol", true, "hey");
        assertEquals("§d● §d[BossChat] §fCarol §8→ §7you§7: §fhey", line);
        assertTrue(line.contains("→"), "DM shows a direction cue");
        assertTrue(line.contains("you"), "DM inbound is addressed to you");
    }

    @Test
    void scopeIsNeverRepeatedAsTextTagOnAnyScope() {
        // The simplification: one clean "[BossChat]" anchor per line, scope carried by colour only.
        for (String scope : new String[] {"global", "server", "dm"}) {
            String line = BossChatFormat.inbound(scope, "Bob", true, "hi");
            assertTrue(line.contains("[BossChat] "), scope + " keeps the single brand anchor");
            assertFalse(line.contains("[BossChat·"), scope + " must NOT carry a ·scope text tag: " + line);
            assertFalse(line.contains("·server"), "no ·server text: " + line);
            assertFalse(line.contains("·DM"), "no ·DM text: " + line);
        }
    }

    @Test
    void unknownScopeFallsBackToGlobalStyling() {
        assertEquals(BossChatFormat.inbound("global", "A", true, "x"),
            BossChatFormat.inbound("weird-unknown", "A", true, "x"));
        assertEquals(BossChatFormat.inbound("global", "A", true, "x"),
            BossChatFormat.inbound(null, "A", true, "x"));
    }

    @Test
    void theThreeScopesAreVisuallyDistinct() {
        String g = BossChatFormat.inbound("global", "A", true, "x");
        String s = BossChatFormat.inbound("server", "A", true, "x");
        String d = BossChatFormat.inbound("dm", "A", true, "x");
        assertNotEquals(g, s);
        assertNotEquals(g, d);
        assertNotEquals(s, d);
        assertTrue(g.startsWith("§b"), "global aqua");
        assertTrue(s.startsWith("§a"), "server green");
        assertTrue(d.startsWith("§d"), "dm purple");
    }

    // ---- sender markers: verified / unverified / boss ------------------------------------------------

    @Test
    void regularVerifiedSenderIsPlainWhite() {
        assertEquals("§fSteve", BossChatFormat.sender("Steve", true));
    }

    @Test
    void unverifiedSenderRecedesMutedMarkerAndGreyName() {
        String who = BossChatFormat.sender("Mallory", false);
        // Dim-grey italic marker AND a grey (NOT white) name, so the whole identity recedes.
        assertEquals("§8§o(unverified)§r §7Mallory", who);
        assertTrue(who.contains("§8§o"), "marker is dim-grey italic (secondary, not a warning label)");
        assertTrue(who.contains("§r §7"), "name is grey, not the white used for verified senders");
        assertFalse(who.contains("§f"), "unverified name must not use the white anchor colour");
        assertFalse(who.toLowerCase().contains("warning"));
    }

    @Test
    void verifiedNameIsTheWhiteAnchorUnverifiedIsNot() {
        assertTrue(BossChatFormat.sender("Steve", true).startsWith("§f"), "verified = white anchor");
        assertTrue(BossChatFormat.sender("Steve", false).endsWith("§7Steve"), "unverified = grey, receded");
    }

    @Test
    void bossKeepsServerBoldBlackPrefixAndGetsGoldNameAccent() {
        String who = BossChatFormat.sender(BOSS + "WaterBoss11", true);
        assertEquals("§0§l[Boss]§r §6WaterBoss11", who);
        assertTrue(who.startsWith("§0§l[Boss]§r "), "server bold-black [Boss] prefix is kept verbatim");
        assertTrue(who.contains("§6"), "name gets a subtle gold accent");
    }

    @Test
    void bossPrefixDriftDegradesSafelyToPlainName() {
        // If the server's exact prefix ever changes, we must not throw or mangle — just show it as-is.
        String weird = "§0§l[BOSS]§r Someone"; // note: not the exact constant
        assertEquals("§f" + weird, BossChatFormat.sender(weird, true));
    }

    @Test
    void nullSenderDoesNotThrow() {
        assertEquals("§f", BossChatFormat.sender(null, true));
    }

    // ---- outbound echo -------------------------------------------------------------------------------

    @Test
    void outboundEchoLabelsYouAndDmDirectionIsReversed() {
        assertEquals("§b● §b[BossChat] §7you§7: §fhi",
            BossChatFormat.outbound("global", null, true, "hi"));
        String dm = BossChatFormat.outbound("dm", "Dave", true, "yo");
        assertEquals("§d● §d[BossChat] §7you §8→ §fDave§7: §fyo", dm);
        // Inbound DM is "sender → you"; outbound DM is "you → recipient".
        assertTrue(dm.indexOf("you") < dm.indexOf("Dave"), "outbound reads you → recipient");
    }

    @Test
    void unverifiedSelfIsMarkedInTheEcho() {
        String me = BossChatFormat.self(false);
        assertEquals("§8§o(unverified)§r §7you", me);
    }

    // ---- party scope + party events ------------------------------------------------------------------

    @Test
    void partyScopeUsesADistinctGoldAnchor() {
        assertEquals("§6● §6[BossChat] §fAlice§7: §fhi",
            BossChatFormat.inbound("party", "Alice", true, "hi"));
        // Distinct from the other three scopes (a NEW colour, gold §6).
        String p = BossChatFormat.inbound("party", "A", true, "x");
        assertTrue(p.startsWith("§6"), "party = gold");
        assertNotEquals(BossChatFormat.inbound("global", "A", true, "x"), p);
        assertNotEquals(BossChatFormat.inbound("server", "A", true, "x"), p);
        assertNotEquals(BossChatFormat.inbound("dm", "A", true, "x"), p);
    }

    @Test
    void partyChatKeepsTheUnverifiedMarker() {
        String line = BossChatFormat.inbound("party", "Eve", false, "hi");
        assertTrue(line.contains("§8§o(unverified)§r §7Eve"), "the honesty marker stays in party chat too");
    }

    @Test
    void partyInvitePromptShowsInviterAndHowToRespond() {
        assertEquals(
            "§6● §6[BossChat] §fBob §7invited you to a party §8· §7?bossaddon party §faccept §7or §fdecline",
            BossChatFormat.partyInvite("Bob", true));
        // An unverified inviter is still marked in the prompt.
        assertTrue(BossChatFormat.partyInvite("Eve", false).contains("§8§o(unverified)§r §7Eve"));
    }

    @Test
    void partyJoinedAndLeftAndDisbanded() {
        assertEquals("§6● §6[BossChat] §fCarl §7joined the party §8(§73§8)",
            BossChatFormat.partyJoined("Carl", true, 3));
        assertEquals("§6● §6[BossChat] §fDan §7left the party",
            BossChatFormat.partyLeft("Dan", true, false));
        assertEquals("§6● §6[BossChat] §7the party disbanded",
            BossChatFormat.partyLeft("Dan", true, true));
    }

    @Test
    void partyListRostersEveryMemberWithMarkers() {
        String list = BossChatFormat.partyList(new String[] {"A", "B"}, new boolean[] {true, false});
        assertEquals("§6● §6[BossChat] §7party §8(§72§8)§7: §fA§7, §8§o(unverified)§r §7B", list);
        // Empty / null-safe.
        assertEquals("§6● §6[BossChat] §7party §8(§70§8)§7: ", BossChatFormat.partyList(null, null));
    }

    // ---- system/status lines are structurally distinct from chat -------------------------------------

    @Test
    void systemLinesAreItalicBossChatDashFormWithNoChatBadge() {
        String[] status = {
            BossChatFormat.connecting(),
            BossChatFormat.connectedVerified(),
            BossChatFormat.connectedUnverified(),
            BossChatFormat.disconnected(),
            BossChatFormat.rejected("bad"),
            BossChatFormat.notConnected(),
            BossChatFormat.system("hello"),
        };
        for (String s : status) {
            assertFalse(s.contains("[BossChat"), "status must NOT use the chat badge: " + s);
            assertTrue(s.contains("BossChat " + "§8§o" + "—") || s.contains("— "), "status uses the em-dash form: " + s);
            assertTrue(s.contains("§o"), "status is italic (distinct from chat): " + s);
            assertFalse(s.contains(": §f"), "status has no 'name: body' chat structure: " + s);
        }
    }

    @Test
    void connectingIsYellowDotConnectedIsGreenRejectedIsRed() {
        assertTrue(BossChatFormat.connecting().startsWith("§e●"), "connecting = yellow dot");
        assertTrue(BossChatFormat.connectedVerified().startsWith("§a●"), "connected = green dot");
        assertTrue(BossChatFormat.disconnected().startsWith("§7●"), "disconnected = grey dot");
        assertTrue(BossChatFormat.rejected("x").startsWith("§c●"), "rejected = red dot");
    }

    @Test
    void connectedVerifiedKeepsTheUsefulHint() {
        String s = BossChatFormat.connectedVerified();
        assertTrue(s.contains("connected"));
        assertTrue(s.contains("?bossaddon chat"), "keeps the how-to hint");
        assertTrue(s.contains("verified"));
    }

    @Test
    void rejectedIncludesReasonAndHandlesNull() {
        assertTrue(BossChatFormat.rejected("bad invite").contains("bad invite"));
        assertTrue(BossChatFormat.rejected(null).contains("unknown"));
    }

    @Test
    void verifiedOnlyDistinguishesForcedVsAuthFailure() {
        assertTrue(BossChatFormat.verifiedOnly(true).toLowerCase().contains("offline accounts"));
        assertTrue(BossChatFormat.verifiedOnly(false).toLowerCase().contains("mojang auth failed"));
    }

    // ---- ?bossaddon chat command feedback shares the status style -----------------------------------

    @Test
    void commandFeedbackUsesTheStatusFormNotTheChatBadge() {
        String[] cmd = {
            BossChatFormat.scopeChanged("global", false),
            BossChatFormat.scopeChanged("off", true),
            BossChatFormat.reconnecting(),
            BossChatFormat.statusReport("connected", "global"),
            BossChatFormat.notEnabled(),
        };
        for (String s : cmd) {
            assertFalse(s.contains("[BossChat"), "command feedback is a status line, not a chat message: " + s);
            assertTrue(s.contains("●"), "still leads with the recognition dot: " + s);
            assertTrue(s.contains("§o"), "italic status form: " + s);
        }
    }

    @Test
    void scopeChangedIsGreenWhenActiveGreyWhenOff() {
        assertTrue(BossChatFormat.scopeChanged("global", false).startsWith("§a●"), "active scope = green dot");
        assertTrue(BossChatFormat.scopeChanged("off", true).startsWith("§7●"), "off = grey dot");
        assertTrue(BossChatFormat.scopeChanged("global", false).contains("global"));
    }

    @Test
    void statusReportShowsBothStateAndScope() {
        String s = BossChatFormat.statusReport("connected (unverified)", "server");
        assertTrue(s.contains("connected (unverified)"));
        assertTrue(s.contains("server"));
    }

    // ---- the Minecraft italic-reset rule -------------------------------------------------------------

    @Test
    void italicIsReappliedAfterEveryColourInStatusLines() {
        // In MC a colour code clears italic. So in an italic status line, every "§x" that starts a visible
        // segment must be immediately followed by "§o". Walk the string and verify no colour code is left
        // "bare" (a colour directly followed by visible text) once we're in the italic status body.
        String s = BossChatFormat.connectedVerified();
        // After the leading "§a● " state dot, the remainder is the italic body.
        int bodyStart = s.indexOf("§8§o"); // "BossChat" scaffold begins here
        assertTrue(bodyStart >= 0, "status body present");
        String body = s.substring(bodyStart);
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) == '§' && isColour(body.charAt(i + 1))) {
                // the next token after a colour must be another § (either §o italic, or another code) —
                // never bare text, which would render non-italic.
                boolean followedByCode = i + 2 < body.length() && body.charAt(i + 2) == '§';
                assertTrue(followedByCode,
                    "colour §" + body.charAt(i + 1) + " at " + i + " must be followed by §o to keep italic: " + body);
            }
        }
    }

    @Test
    void itHelperReAppliesItalicToAColour() {
        assertEquals("§7§o", BossChatFormat.it("§7"));
        assertEquals("§c§o", BossChatFormat.it("§c"));
    }

    // ---- glyph safety: only MC-font-proven symbols appear --------------------------------------------

    @Test
    void onlyProvenGlyphsAreUsed() {
        String all = String.join(" ",
            BossChatFormat.inbound("global", "A", true, "b"),
            BossChatFormat.inbound("server", "A", true, "b"),
            BossChatFormat.inbound("dm", "A", true, "b"),
            BossChatFormat.outbound("dm", "A", true, "b"),
            BossChatFormat.connecting(),
            BossChatFormat.connectedVerified(),
            BossChatFormat.connectedUnverified(),
            BossChatFormat.rejected("x"),
            BossChatFormat.verifiedOnly(true),
            BossChatFormat.verifiedOnly(false),
            BossChatFormat.system("hi"),
            BossChatFormat.scopeChanged("global", false),
            BossChatFormat.reconnecting(),
            BossChatFormat.statusReport("connected", "global"),
            BossChatFormat.notEnabled(),
            BossChatFormat.inbound("party", "A", true, "b"),
            BossChatFormat.partyInvite("A", true),
            BossChatFormat.partyJoined("A", true, 2),
            BossChatFormat.partyLeft("A", true, false),
            BossChatFormat.partyList(new String[] {"A", "B"}, new boolean[] {true, false}),
            BossChatFormat.warpRequest("A", true, "play.example.com:25565"),
            BossChatFormat.warpSent("A"),
            BossChatFormat.warpSent(null),
            BossChatFormat.warpDeclined(),
            BossChatFormat.warpDeclinedBy("A", false),
            BossChatFormat.warpNonePending(),
            BossChatFormat.warpNotOnServer(),
            BossChatFormat.warpConnecting("play.example.com:25565"),
            BossChatFormat.dmNoTarget(),
            BossChatFormat.dmScopeSet("Steve"),
            // Connection-state and identity messages must obey the same font rule as everything else.
            BossChatFormat.disconnected(),
            BossChatFormat.connectingUnverified(),
            BossChatFormat.disabled(),
            BossChatFormat.coldStart(15, 60),
            BossChatFormat.coldStartProgress(21, 7),
            BossChatFormat.coldStartReady(24),
            BossChatFormat.coldStartTimedOut(120),
            BossChatFormat.unreachable(),
            BossChatFormat.identityChanged("SFBOSS"),
            BossChatFormat.forcedOffline(),
            BossChatFormat.warpPendingHint("accept"),
            BossChatFormat.warpPendingHint("decline"));
        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);
            if (c > 0x7F && c != '§') { // '§' is the formatting marker; any OTHER non-ASCII must be proven
                boolean proven = "●·→…—".indexOf(c) >= 0;
                assertTrue(proven, "non-ASCII glyph '" + c + "' (U+" + Integer.toHexString(c)
                    + ") is not in the MC-font-proven set ●·→…—");
            }
        }
    }

    private static boolean isColour(char c) {
        return "0123456789abcdefABCDEF".indexOf(c) >= 0;
    }
}
