package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A warp request and a party invite are answered by DIFFERENT commands, and the relay holds no warp state at
 * all — so answering a warp with "party accept" gets "You have no pending party invite", a message about a
 * different feature, which reads as the warp being broken. These lock in the hint that prevents that dead end.
 */
class WarpHintTest {

    @Test
    void theHintNamesTheWarpCommandAndTheMatchingVerb() {
        String accept = BossChatFormat.warpPendingHint("accept");
        assertTrue(accept.contains("?bossaddon party warp accept"),
            "must name the exact command to use: " + accept);
        assertTrue(accept.contains("warp request"), "must say what is pending: " + accept);

        String decline = BossChatFormat.warpPendingHint("decline");
        assertTrue(decline.contains("?bossaddon party warp decline"),
            "the verb follows what the user actually typed: " + decline);
        assertFalse(decline.contains("warp accept"),
            "declining must not be told to accept: " + decline);
    }

    @Test
    void anUnknownVerbFallsBackToAcceptRatherThanEmittingGarbage() {
        String hint = BossChatFormat.warpPendingHint(null);
        assertTrue(hint.contains("?bossaddon party warp accept"), hint);
    }

    @Test
    void theWarpAndPartyNonePendingMessagesAreDistinguishable() {
        // These two strings are the whole reason the bug was hard to place: if they read alike, a user
        // cannot tell which feature answered. The warp one must never mention a party invite.
        String warp = BossChatFormat.warpNonePending();
        assertTrue(warp.contains("warp request"), warp);
        assertFalse(warp.toLowerCase().contains("party invite"),
            "the warp message must not be confusable with the relay's party-invite reply: " + warp);
    }
}
