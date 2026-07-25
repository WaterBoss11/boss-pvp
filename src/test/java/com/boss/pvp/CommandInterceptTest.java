package com.boss.pvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the "?" interception rule ({@link BossAddonInit}): ANY line whose first character is "?" is intercepted
 * and never sent; a recognized ?bossaddon command runs, anything else gets a local "unknown" notice. Uses only
 * the pure predicates ({@code shouldIntercept}, {@code isRecognizedCommand} — a dry Brigadier parse, no client).
 */
class CommandInterceptTest {

    // ---- shouldIntercept: EVERY line starting with "?" is intercepted (never sent) --------------------

    @Test
    void everyLineStartingWithPrefixIsIntercepted() {
        for (String m : new String[]{
                "?", "?bossaddon", "?bossaddon help", "?hi", "?hello everyone", "?gg",
                "?bossaddonx", "?123", "?!@#", "?bossaddon chat g hi"}) {
            assertTrue(BossAddonInit.shouldIntercept(m), "must intercept: " + m);
        }
    }

    @Test
    void linesNotStartingWithPrefixAreNotIntercepted() {
        for (String m : new String[]{"hi", "hello", "/bossaddon help", "bossaddon help", "", " ?leadingspace", "."}) {
            assertFalse(BossAddonInit.shouldIntercept(m), "must NOT intercept: " + m);
        }
        assertFalse(BossAddonInit.shouldIntercept(null));
    }

    // ---- isRecognizedCommand: valid ?bossaddon commands vs unknown (line minus the "?") ---------------

    @Test
    void validCommandsAreRecognized() {
        for (String cmd : new String[]{
                "bossaddon", "bossaddon help", "bossaddon help chat", "bossaddon pvp on", "bossaddon pvp off",
                "bossaddon utility on", "bossaddon utility off", "bossaddon menu simple", "bossaddon menu advanced",
                "bossaddon chat global", "bossaddon chat server", "bossaddon chat off", "bossaddon chat disable",
                "bossaddon party list", "bossaddon party accept", "bossaddon party decline"}) {
            assertTrue(BossAddonInit.isRecognizedCommand(cmd), "should be recognized: " + cmd);
        }
    }

    @Test
    void unknownInputIsNotRecognized() {
        // "bossaddon" is optional now, so each of these is canonicalized to "bossaddon <it>" and still fails.
        for (String bad : new String[]{
                "hi", "foo", "bossaddon xyz", "bossaddonx", "notacommand", "bossaddon bogus", "nope on"}) {
            assertFalse(BossAddonInit.isRecognizedCommand(bad), "should NOT be recognized: " + bad);
        }
    }

    // ---- Part 1: "bossaddon" is optional — "?" == "?bossaddon", "? help" == "?bossaddon help", etc. ------

    @Test
    void bossaddonPrefixIsOptional() {
        // canonical() prepends the root when it's absent.
        assertEquals("bossaddon", BossAddonInit.canonical(""));         // "?"  -> overview
        assertEquals("bossaddon", BossAddonInit.canonical("   "));
        assertEquals("bossaddon", BossAddonInit.canonical(null));
        assertEquals("bossaddon help", BossAddonInit.canonical("help"));
        assertEquals("bossaddon pvp on", BossAddonInit.canonical("pvp on"));
        assertEquals("bossaddon help", BossAddonInit.canonical("bossaddon help"));   // already full -> unchanged
        assertEquals("bossaddon", BossAddonInit.canonical("bossaddon"));

        // Both short and full forms resolve identically.
        for (String tail : new String[]{"", "help", "help chat", "pvp on", "utility off",
                "menu simple", "chat global", "party list", "party accept"}) {
            assertTrue(BossAddonInit.isRecognizedCommand(tail), "short form recognized: '" + tail + "'");
            String full = tail.isEmpty() ? "bossaddon" : "bossaddon " + tail;
            assertTrue(BossAddonInit.isRecognizedCommand(full), "full form recognized: '" + full + "'");
        }
    }

    // ---- Part 2: tab-complete suggestion logic (given partial input) ------------------------------------

    @Test
    void tabSuggestionsCoverSubcommandsAndArgs() {
        // Empty input ("?") suggests every top-level subcommand (bossaddon is optional).
        java.util.List<String> top = BossAddonInit.suggest("");
        assertTrue(top.containsAll(java.util.List.of("help", "pvp", "utility", "menu", "chat", "party")),
            "top-level suggestions: " + top);

        // A partial token completes to matching subcommands only.
        java.util.List<String> p = BossAddonInit.suggest("p");
        assertTrue(p.contains("party") && p.contains("pvp"), "p -> party/pvp: " + p);
        assertFalse(p.contains("help"), "'help' does not start with p");

        // Per-subcommand argument suggestions.
        java.util.List<String> pvpArgs = BossAddonInit.suggest("pvp ");
        assertTrue(pvpArgs.contains("on") && pvpArgs.contains("off") && pvpArgs.size() == 2, "pvp args: " + pvpArgs);
        java.util.List<String> menuArgs = BossAddonInit.suggest("menu ");
        assertTrue(menuArgs.contains("simple") && menuArgs.contains("advanced"), "menu args: " + menuArgs);

        // "bossaddon" is optional for suggestions too — short and full forms give the same completions.
        assertEquals(BossAddonInit.suggest("p"), BossAddonInit.suggest("bossaddon p"));
        assertEquals(BossAddonInit.suggest("pvp "), BossAddonInit.suggest("bossaddon pvp "));

        // No match -> no suggestions.
        assertTrue(BossAddonInit.suggest("zzz").isEmpty(), "unmatched token -> empty");
    }

    // ---- the two behaviours together --------------------------------------------------------------------

    @Test
    void validPrefixedMessageIsInterceptedAndRecognized() {
        // "?bossaddon help" -> intercepted AND recognized -> runs as a command.
        String typed = "?bossaddon help";
        assertTrue(BossAddonInit.shouldIntercept(typed));
        assertTrue(BossAddonInit.isRecognizedCommand(typed.substring(BossAddonInit.PREFIX.length())));
    }

    @Test
    void invalidPrefixedMessageIsInterceptedButNotRecognized() {
        // Someone types "?hello" (meaning to chat): it is intercepted (never sent) but is NOT a recognized
        // command -> the sender gets a local "Unknown command. Try ?bossaddon help." notice instead.
        String typed = "?hello";
        assertTrue(BossAddonInit.shouldIntercept(typed), "intercepted, never sent");
        assertFalse(BossAddonInit.isRecognizedCommand(typed.substring(BossAddonInit.PREFIX.length())),
            "unrecognized -> feedback path (not sent as chat)");
    }

    // ---- outbound guarantee: nothing whose first char is "?" can reach the chat-relay send path ---------

    @Test
    void nothingStartingWithPrefixCanReachOutboundChat() {
        // The relay redirect (sendTyped -> RelaySanitizer) in ChatScreenMixin only runs for lines that
        // shouldIntercept() REJECTS. Since every "?" line is intercepted (and the mixin cancels + returns
        // first), no "?"-prefixed line can ever reach the outbound send path. This asserts that guarantee's
        // precondition holds for chat-looking "?" inputs.
        for (String m : new String[]{"?", "?hi", "?hello", "?bossaddon help", "?gg wp", "?anything at all"}) {
            assertTrue(BossAddonInit.shouldIntercept(m), "must be intercepted so it can't be sent: " + m);
        }
    }

    // ---- explicit "party invite <user>" keyword (additive; bare "party <user>" kept as an alias) ---------

    @Test
    void partyInviteKeywordAndBareShortcutBothRecognized() {
        // Explicit keyword, short and full forms.
        assertTrue(BossAddonInit.isRecognizedCommand("party invite Steve"), "party invite <user>");
        assertTrue(BossAddonInit.isRecognizedCommand("bossaddon party invite Steve"), "bossaddon party invite <user>");
        // Bare shortcut still works (kept as an alias, not replaced).
        assertTrue(BossAddonInit.isRecognizedCommand("party Steve"), "bare party <user> still invites");
        // "invite" is offered as a party subcommand, identically for the short and full forms.
        assertTrue(BossAddonInit.suggest("party ").contains("invite"), "party subs: " + BossAddonInit.suggest("party "));
        assertEquals(BossAddonInit.suggest("party "), BossAddonInit.suggest("bossaddon party "));
        // A partial narrows to it.
        assertEquals(java.util.List.of("invite"), BossAddonInit.suggest("party inv"));
    }

    // ---- party warp: every form must reach an executable node -------------------------------------------

    @Test
    void partyWarpFormsAreAllRecognized() {
        // "warp" bare = propose to the whole party; "warp <user>" = propose to one member; accept/decline
        // answer an incoming request. All four, in both the short and full prefix forms.
        for (String tail : new String[]{"party warp", "party warp accept", "party warp decline", "party warp Steve"}) {
            assertTrue(BossAddonInit.isRecognizedCommand(tail), "short form recognized: '" + tail + "'");
            assertTrue(BossAddonInit.isRecognizedCommand("bossaddon " + tail), "full form recognized: '" + tail + "'");
        }
    }

    @Test
    void partyWarpSuggestsItsResponses() {
        // "warp" is offered under party, and its only literal children are the two responses — a member
        // name is a free-form argument, so it must not appear as a completion.
        assertTrue(BossAddonInit.suggest("party ").contains("warp"), "party subs: " + BossAddonInit.suggest("party "));
        assertEquals(java.util.List.of("accept", "decline"), BossAddonInit.suggest("party warp "));
        assertEquals(BossAddonInit.suggest("party warp "), BossAddonInit.suggest("bossaddon party warp "));
    }

    @Test
    void partyWarpLiteralsWinOverTheUserArgument() {
        // Brigadier matches the accept/decline literals before the <user> argument, so "warp accept" can
        // never be parsed as "propose a warp to a player named accept".
        assertTrue(BossAddonInit.isRecognizedCommand("party warp accept"));
        // And a trailing extra token is not a command at all (it would silently do the wrong thing).
        assertFalse(BossAddonInit.isRecognizedCommand("party warp accept now"));
    }
}
