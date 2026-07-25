package com.boss.pvp.relay;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Clickable action chips appended to a prompt, so a prompt that asks a question can be answered by clicking
 * instead of retyping a command exactly.
 *
 * <p><b>Why this exists.</b> A party invite and a warp request are answered by two commands that differ by one
 * word ({@code party accept} vs {@code party warp accept}). Both prompts already print the exact command and it
 * still didn't survive contact with muscle memory: a warp answered with {@code party accept} reaches the relay's
 * party path and comes back "You have no pending party invite" — a message about a different feature, which
 * reads as the warp being broken. A chip removes the retyping step that causes it.
 *
 * <p><b>SUGGEST_COMMAND, never RUN_COMMAND.</b> The addon's commands start with {@code "?"}, not {@code "/"}.
 * {@code SuggestCommand} only fills the chat input, so the line still goes through {@code ChatScreenMixin} —
 * which preserves both the interception guarantee (nothing beginning with {@code "?"} ever reaches the server)
 * and warp's explicit-consent property, since the user still presses Enter. {@code RunCommand} bypasses the
 * chat screen, and a {@code "?"} line dispatched that way risks being sent as ORDINARY CHAT — publicly leaking
 * the command. That would be a privacy regression, not a convenience.
 *
 * <p><b>The legacy-§ trap.</b> The message bodies are legacy §-coded strings (see {@link BossChatFormat}), and
 * a §-code inside a component's text bleeds into how following siblings render. A chip's own text therefore
 * carries NO § codes — it takes its colour from {@link net.minecraft.network.chat.Style} — and the separator
 * before it emits a §r first, so the chip's style applies cleanly instead of inheriting the tail of the message.
 *
 * <p>Pure component construction: no client, no registries, so it is unit-testable offline.
 */
public final class ChatActions {

    private ChatActions() {}

    /** One clickable chip: the visible label and the exact command it fills in. */
    public record Action(String label, String command) {}

    /** The four answers the two prompts need. Commands are spelled in full — that is the point. */
    public static final Action PARTY_ACCEPT = new Action("Accept", "?bossaddon party accept");
    public static final Action PARTY_DECLINE = new Action("Decline", "?bossaddon party decline");
    public static final Action WARP_ACCEPT = new Action("Accept", "?bossaddon party warp accept");
    public static final Action WARP_DECLINE = new Action("Decline", "?bossaddon party warp decline");

    /**
     * The prompt text with chips appended. The text still names the commands, so typing keeps working and
     * anyone reading a log or screenshot sees them; the chips are only an affordance on top.
     */
    public static Component prompt(String legacyMessage, List<Action> actions) {
        MutableComponent out = Component.literal(legacyMessage == null ? "" : legacyMessage);
        for (Action a : actions == null ? List.<Action>of() : actions) {
            if (a == null || a.command() == null || a.command().isBlank()) continue;
            out.append(Component.literal("§r "));   // reset first: see the legacy-§ note above
            out.append(chip(a));
        }
        return out;
    }

    /** Convenience for the usual two-answer prompt. */
    public static Component prompt(String legacyMessage, Action accept, Action decline) {
        return prompt(legacyMessage, List.of(accept, decline));
    }

    /**
     * A single chip. Underlined and coloured so it reads as clickable, with the command in the tooltip so
     * hovering shows exactly what will be filled in — nothing is hidden behind the label.
     */
    static Component chip(Action a) {
        String label = a.label() == null || a.label().isBlank() ? a.command() : a.label();
        return Component.literal("[" + label + "]").withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(Boolean.TRUE)
            .withClickEvent(new ClickEvent.SuggestCommand(a.command()))
            .withHoverEvent(new HoverEvent.ShowText(
                Component.literal("Click to fill in: " + a.command()))));
    }
}
