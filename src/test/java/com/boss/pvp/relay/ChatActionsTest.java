package com.boss.pvp.relay;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clickable answer chips. Two properties are load-bearing and both are asserted here rather than trusted:
 * the chip must carry the EXACT command, and it must use SUGGEST_COMMAND — never RUN_COMMAND, which for a
 * {@code "?"}-prefixed command risks being dispatched as ordinary chat and leaking it to the server.
 */
class ChatActionsTest {

    /** Every clickable descendant of a component, flattened. */
    private static List<Component> clickables(Component root) {
        return root.toFlatList().stream()
            .filter(c -> c.getStyle().getClickEvent() != null)
            .toList();
    }

    @Test
    void aChipCarriesTheExactCommandItAdvertises() {
        Component c = ChatActions.chip(new ChatActions.Action("Accept", "?bossaddon party warp accept"));
        ClickEvent click = c.getStyle().getClickEvent();
        assertNotNull(click, "the chip must be clickable");
        ClickEvent.SuggestCommand suggest = assertInstanceOf(ClickEvent.SuggestCommand.class, click,
            "must be SuggestCommand: RunCommand would bypass the chat screen");
        assertEquals("?bossaddon party warp accept", suggest.command(),
            "the filled-in command must be exactly what the label promises");
    }

    @Test
    void chipsNeverUseRunCommand() {
        // RUN_COMMAND on a "?" line risks being sent as ORDINARY CHAT — a privacy regression, since the
        // whole point of the "?" prefix is that such lines never reach the server.
        for (ChatActions.Action a : List.of(
                ChatActions.PARTY_ACCEPT, ChatActions.PARTY_DECLINE,
                ChatActions.WARP_ACCEPT, ChatActions.WARP_DECLINE)) {
            ClickEvent click = ChatActions.chip(a).getStyle().getClickEvent();
            assertFalse(click instanceof ClickEvent.RunCommand,
                a.command() + " must not run directly");
            assertInstanceOf(ClickEvent.SuggestCommand.class, click);
        }
    }

    @Test
    void theFourStandardActionsSpellTheirCommandsInFull() {
        // The whole bug was a one-word difference between two commands, so these must be unambiguous.
        assertEquals("?bossaddon party accept", ChatActions.PARTY_ACCEPT.command());
        assertEquals("?bossaddon party decline", ChatActions.PARTY_DECLINE.command());
        assertEquals("?bossaddon party warp accept", ChatActions.WARP_ACCEPT.command());
        assertEquals("?bossaddon party warp decline", ChatActions.WARP_DECLINE.command());
    }

    @Test
    void aPromptKeepsItsTextAndGainsExactlyTwoChips() {
        String body = BossChatFormat.warpRequest("x0mx", true, "divineprison.net");
        Component prompt = ChatActions.prompt(body, ChatActions.WARP_ACCEPT, ChatActions.WARP_DECLINE);

        String flat = prompt.getString();
        assertTrue(flat.contains("wants you to join them on"), "original message text is preserved: " + flat);
        assertTrue(flat.contains("[Accept]") && flat.contains("[Decline]"), flat);

        List<Component> chips = clickables(prompt);
        assertEquals(2, chips.size(), "exactly one chip per answer");
        assertEquals("?bossaddon party warp accept",
            ((ClickEvent.SuggestCommand) chips.get(0).getStyle().getClickEvent()).command());
        assertEquals("?bossaddon party warp decline",
            ((ClickEvent.SuggestCommand) chips.get(1).getStyle().getClickEvent()).command());
    }

    @Test
    void thePartyPromptOffersThePartyCommandsAndTheWarpPromptTheWarpOnes() {
        // Crossing these would recreate the original bug with a click instead of a typo.
        Component party = ChatActions.prompt(
            BossChatFormat.partyInvite("x0mx", true), ChatActions.PARTY_ACCEPT, ChatActions.PARTY_DECLINE);
        for (Component chip : clickables(party)) {
            String cmd = ((ClickEvent.SuggestCommand) chip.getStyle().getClickEvent()).command();
            assertFalse(cmd.contains("warp"), "a party invite must not offer a warp command: " + cmd);
        }

        Component warp = ChatActions.prompt(
            BossChatFormat.warpRequest("x0mx", true, "a.b"), ChatActions.WARP_ACCEPT, ChatActions.WARP_DECLINE);
        for (Component chip : clickables(warp)) {
            String cmd = ((ClickEvent.SuggestCommand) chip.getStyle().getClickEvent()).command();
            assertTrue(cmd.contains("warp"), "a warp request must only offer warp commands: " + cmd);
        }
    }

    @Test
    void chipTextCarriesNoLegacyFormattingCodes() {
        // A § code inside the chip's own text would override the Style that makes it look clickable, and
        // §-codes bleed into following siblings.
        for (ChatActions.Action a : List.of(ChatActions.WARP_ACCEPT, ChatActions.PARTY_DECLINE)) {
            assertFalse(ChatActions.chip(a).getString().contains("§"),
                "chip label must be plain text: " + a);
        }
    }

    @Test
    void chipsAreStyledAsClickable() {
        var style = ChatActions.chip(ChatActions.WARP_ACCEPT).getStyle();
        assertTrue(style.isUnderlined(), "an underline is the affordance that it can be clicked");
        assertNotNull(style.getColor(), "colour comes from Style, not from a § code");
        assertNotNull(style.getHoverEvent(), "hovering must reveal the command being filled in");
    }

    @Test
    void degenerateInputProducesAPlainMessageRatherThanThrowing() {
        // A missing/blank command must not yield a chip that does nothing when clicked.
        assertEquals(0, clickables(ChatActions.prompt("hello", List.of())).size());
        assertEquals(0, clickables(ChatActions.prompt("hello", List.of(new ChatActions.Action("X", "")))).size());
        assertEquals(0, clickables(ChatActions.prompt("hello", (List<ChatActions.Action>) null)).size());
        assertEquals("", ChatActions.prompt(null, List.of()).getString());
    }

    @Test
    void aChipWithNoLabelFallsBackToShowingTheCommand() {
        assertTrue(ChatActions.chip(new ChatActions.Action("", "?bossaddon party accept"))
            .getString().contains("?bossaddon party accept"));
    }

    @Test
    void nullClickEventOnPlainTextIsUnchanged() {
        assertNull(Component.literal("plain").getStyle().getClickEvent());
    }
}
