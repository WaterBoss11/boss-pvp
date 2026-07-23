package com.boss.utility.module.world;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.mixin.MultiPlayerGameModeAccessor;
import com.boss.utility.util.NukerShape;
import com.boss.utility.util.Util;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ChoiceSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.RegistryListSetting;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismRotationUtil;
import autismclient.util.AutismWorldGeometry;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.boss.pvp.util.MenuMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Nuker — breaks the blocks around you within a configured shape. Studied and rewritten from Meteor Client's
 * Nuker (https://github.com/MeteorDevelopment/meteor-client, GPL-3.0), adapted to Java + the AUTISM settings
 * API. BossAddon is likewise GPL-3.0; see the README's third-party credits.
 *
 * <p>Defaults are the conservative end of every choice (see {@link NukerShape}): list mode is Whitelist with an
 * empty list, so the module breaks <b>nothing</b> until you add blocks; break path is legit progressive mining
 * (packet/instant mining and cooldown suppression are opt-in and off); one block per tick with a delay; and it
 * only breaks blocks the held tool is correct for, in line of sight.
 */
public final class NukerModule extends Module {

    private int delayTimer = 0;
    private BlockPos lastMiningPos = null;
    private boolean warnedEmptyList = false;
    private int debugCounter = 0;
    // Published each tick for the render thread (COLLECT_SUBMITS runs off the client thread).
    private volatile List<BlockPos> renderTargets = List.of();

    // Filter breakdown from the last collect pass, reported by the debug setting so "nothing happens" is
    // always explainable: every scanned block lands in exactly one of these buckets or becomes a target.
    private static final class Stats {
        int scanned, air, unbreakable, modeFiltered, listFiltered, toolFiltered, reachFiltered;
    }

    public NukerModule() {
        super(BossUtilityAddon.ID + ":nuker", "Nuker", "Breaks nearby blocks within a shape.");

        add(new ChoiceSetting("shape", "Shape", "Sphere", "Sphere", "Cube").group("General"));
        add(new DoubleSetting("range", "Range", 4.0, 1.0, 6.0, 0.5)
            .visibleWhen(() -> !"Cube".equals(choice("shape"))).group("General"));
        add(new IntSetting("rangeUp", "Range up", 1, 0, 8, 1)
            .visibleWhen(() -> "Cube".equals(choice("shape")) && MenuMode.advanced()).group("General"));
        add(new IntSetting("rangeDown", "Range down", 1, 0, 8, 1)
            .visibleWhen(() -> "Cube".equals(choice("shape")) && MenuMode.advanced()).group("General"));
        add(new IntSetting("rangeLeft", "Range left", 1, 0, 8, 1)
            .visibleWhen(() -> "Cube".equals(choice("shape")) && MenuMode.advanced()).group("General"));
        add(new IntSetting("rangeRight", "Range right", 1, 0, 8, 1)
            .visibleWhen(() -> "Cube".equals(choice("shape")) && MenuMode.advanced()).group("General"));
        add(new IntSetting("rangeForward", "Range forward", 1, 0, 8, 1)
            .visibleWhen(() -> "Cube".equals(choice("shape")) && MenuMode.advanced()).group("General"));
        add(new IntSetting("rangeBack", "Range back", 1, 0, 8, 1)
            .visibleWhen(() -> "Cube".equals(choice("shape")) && MenuMode.advanced()).group("General"));

        add(new ChoiceSetting("mode", "Mode", "Flatten", "All", "Flatten")
            .description("Flatten: only break blocks at or above your feet. All: any height in range.").group("General"));
        add(new ChoiceSetting("sortMode", "Sort", "Closest", "None", "Closest", "Furthest", "TopDown")
            .visibleWhen(MenuMode::advanced).group("General"));
        add(new IntSetting("maxPerTick", "Max blocks per tick", 1, 1, 8, 1)
            .description("How many blocks to break each tick. Legit mining still finishes one block at a time; this mainly matters with instant packet mining.").visibleWhen(MenuMode::advanced).group("General"));
        add(new IntSetting("delay", "Delay (ticks)", 2, 0, 20, 1)
            .formatter(v -> v + "t")
            .description("Pause after a block finishes breaking (or between instant-mine bursts). Does not interrupt mining a block in progress.")
            .visibleWhen(MenuMode::advanced).group("General"));
        add(new BoolSetting("debug", "Debug messages", false)
            .description("Chat a once-a-second breakdown of how many blocks were scanned and why they were skipped (list, mode, tool, line of sight). Turn this on if Nuker seems to do nothing.").group("General"));
        add(new DoubleSetting("wallsRange", "Through-wall range", 0.0, 0.0, 6.0, 0.5)
            .description("Break blocks you can't see within this distance (0 = only blocks in line of sight).").visibleWhen(MenuMode::advanced).group("General"));
        add(new BoolSetting("rotate", "Rotate to block", true)
            .description("Face the block before breaking it (looks legit to the server).").visibleWhen(MenuMode::advanced).group("General"));
        add(new BoolSetting("silentRotation", "Silent rotation", true)
            .description("On: rotation is server-side only, your camera never turns. Off: your real camera turns.")
            .visibleWhen(() -> MenuMode.advanced() && bool("rotate")).group("General"));
        add(new BoolSetting("swing", "Swing arm", true).visibleWhen(MenuMode::advanced).group("General"));
        add(new BoolSetting("suitableTools", "Only break with correct tool", true)
            .description("Skip blocks your held item isn't the right tool for (avoids slow, dropless breaks).").visibleWhen(MenuMode::advanced).group("General"));

        add(new ChoiceSetting("listMode", "List mode", "Whitelist", "Whitelist", "Blacklist")
            .description("Whitelist: only break blocks in the list (empty = break nothing — the safe default). Blacklist: break everything except the list.").group("Filter"));
        add(RegistryListSetting.blocks("whitelist", "Whitelist")
            .visibleWhen(() -> "Whitelist".equals(choice("listMode"))).group("Filter"));
        add(RegistryListSetting.blocks("blacklist", "Blacklist")
            .visibleWhen(() -> "Blacklist".equals(choice("listMode"))).group("Filter"));

        add(new BoolSetting("packetMine", "Instant packet mine", false)
            .description("Break by sending start+stop dig packets in one tick instead of mining normally. Fast, but the most anticheat-detectable option.").visibleWhen(MenuMode::advanced).group("Break"));
        add(new BoolSetting("noBreakCooldown", "No break cooldown", false)
            .description("Remove the client's normal delay between block breaks. More suspicious to anticheat.").visibleWhen(MenuMode::advanced).group("Break"));
        add(new BoolSetting("interact", "Interact instead of break", false)
            .description("Right-click (use) the block instead of breaking it. For interactable blocks only.").visibleWhen(MenuMode::advanced).group("Break"));

        add(new BoolSetting("render", "Render targets", true).group("Render"));
        add(new BoolSetting("renderBox", "Render bounding box", true)
            .description("Draw the cube outline (Cube shape only).").visibleWhen(MenuMode::advanced).group("Render"));
        add(new ColorSetting("boxColor", "Box colour", 0x64106A90).group("Render"));
        add(new ColorSetting("breakColor", "Break colour", 0x50FF0000).group("Render"));
        add(new DoubleSetting("lineWidth", "Line width", 1.5, 0.5, 4.0, 0.5).visibleWhen(MenuMode::advanced).group("Render"));

        LevelRenderEvents.COLLECT_SUBMITS.register(this::onRender);
    }

    @Override
    public void onDisable() {
        delayTimer = 0;
        lastMiningPos = null;
        warnedEmptyList = false;
        debugCounter = 0;
        renderTargets = List.of();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null || mc.gui.screen() != null) {
            renderTargets = List.of();
            return;
        }

        if (bool("noBreakCooldown") && mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
            acc.bossutility$setDestroyDelay(0);
        }

        // The one configuration that is guaranteed to do nothing: Whitelist mode with an empty list. Say so
        // once per enable instead of silently idling — "enabled it and nothing happened" was a bug report.
        if (!warnedEmptyList && "Whitelist".equals(choice("listMode")) && blockIds(list("whitelist")).isEmpty()) {
            warnedEmptyList = true;
            AutismClientMessaging.sendPrefixed(
                "§d[Nuker] §7Whitelist is empty — nothing will break. Add blocks to the whitelist or switch List mode to Blacklist.");
        }

        // Collect + publish render targets EVERY tick (the delay gate below only pauses actions, not the
        // overlay or the debug readout — a stale overlay made the module look dead during waits).
        Stats stats = new Stats();
        List<BlockPos> targets = collectTargets(p, level, stats);
        renderTargets = List.copyOf(targets);
        reportDebug(stats, targets.size());

        // A finished legit break (our block became air) is the moment the between-block delay starts.
        if (lastMiningPos != null && level.getBlockState(lastMiningPos).isAir()) {
            lastMiningPos = null;
            delayTimer = integer("delay");
        }
        if (delayTimer > 0) { delayTimer--; return; }

        if (targets.isEmpty()) {
            if (lastMiningPos != null) { mc.gameMode.stopDestroyBlock(); lastMiningPos = null; }
            return;
        }

        if (bool("interact") || bool("packetMine")) {
            // Instant paths: these complete in one action, so maxPerTick bursts + the delay gate apply.
            int max = integer("maxPerTick");
            int done = 0;
            for (BlockPos pos : targets) {
                if (done >= max) break;
                breakInstant(mc, p, pos);
                done++;
            }
            if (done > 0) delayTimer = integer("delay");
            lastMiningPos = null;
        } else {
            // Legit path: progressive mining works ONLY with an uninterrupted continueDestroyBlock stream on
            // ONE block — progress decays the moment you stop, and starting another block cancels the first.
            // So: exactly one block, every tick, until it's gone (v1.14.0 gated this behind the delay timer
            // and burst-started maxPerTick blocks per tick, which reset progress forever — the "Nuker does
            // nothing" bug for any block that isn't instant-break).
            mineLegit(mc, p, targets.get(0));
        }
    }

    // Once-a-second filter breakdown so a silent no-op is always explainable in chat.
    private void reportDebug(Stats s, int targetCount) {
        if (!bool("debug")) { debugCounter = 0; return; }
        if (++debugCounter < 20) return;
        debugCounter = 0;
        AutismClientMessaging.sendPrefixed(String.format(
            "§d[Nuker] §7targets %d §8| scanned %d, air %d, unbreakable %d, mode-skip %d, list-skip %d, tool-skip %d, sight-skip %d",
            targetCount, s.scanned, s.air, s.unbreakable, s.modeFiltered, s.listFiltered, s.toolFiltered, s.reachFiltered));
    }

    private List<BlockPos> collectTargets(LocalPlayer p, Level level, Stats stats) {
        List<BlockPos> out = new ArrayList<>();
        boolean cube = "Cube".equals(choice("shape"));
        double range = decimal("range");
        String mode = choice("mode");
        String listMode = choice("listMode");
        Set<String> listIds = blockIds(list("Whitelist".equals(listMode) ? "whitelist" : "blacklist"));
        boolean suitable = bool("suitableTools");
        double wallsRange = decimal("wallsRange");

        BlockPos center = p.blockPosition();
        Vec3 eye = p.getEyePosition();
        double feetY = p.getY();

        int up, down, left, right, fwd, back;
        if (cube) {
            up = integer("rangeUp"); down = integer("rangeDown");
            left = integer("rangeLeft"); right = integer("rangeRight");
            fwd = integer("rangeForward"); back = integer("rangeBack");
        } else {
            int r = (int) Math.ceil(range);
            up = down = left = right = fwd = back = r;
        }
        // For the sphere we iterate the enclosing cube and reject by radius; for the cube the loop bounds
        // (which are already the shape) are per-face, so no extra containment test is needed.
        double maxReachSq = Math.max(range, wallsRange);
        maxReachSq = cube ? Double.MAX_VALUE : maxReachSq * maxReachSq;

        for (int dx = -left; dx <= right; dx++) {
            for (int dz = -back; dz <= fwd; dz++) {
                for (int dy = -down; dy <= up; dy++) {
                    if (!cube && !NukerShape.inSphere(dx, dy, dz, range)) continue;
                    stats.scanned++;
                    BlockPos bp = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(bp);
                    if (state.isAir()) { stats.air++; continue; }
                    float hardness = state.getDestroySpeed(level, bp);
                    if (hardness < 0.0f) { stats.unbreakable++; continue; }   // bedrock etc.
                    if (!NukerShape.flattenAllows(mode, bp.getY(), feetY)) { stats.modeFiltered++; continue; }
                    boolean inList = matchesBlock(state.getBlock(), listIds);
                    if (!NukerShape.listAllows(listMode, inList)) { stats.listFiltered++; continue; }
                    if (suitable && !p.getMainHandItem().isCorrectToolForDrops(state)) { stats.toolFiltered++; continue; }

                    Vec3 c = Vec3.atCenterOf(bp);
                    double distSq = eye.distanceToSqr(c);
                    if (!cube && distSq > maxReachSq) { stats.reachFiltered++; continue; }
                    if (!inReach(level, p, eye, bp, c, range, wallsRange, distSq)) { stats.reachFiltered++; continue; }

                    out.add(bp.immutable());
                }
            }
        }

        String sortMode = choice("sortMode");
        if (!"None".equals(sortMode)) {
            out.sort((a, b) -> Double.compare(
                NukerShape.sortScore(sortMode, eye.distanceToSqr(Vec3.atCenterOf(a)), a.getY()),
                NukerShape.sortScore(sortMode, eye.distanceToSqr(Vec3.atCenterOf(b)), b.getY())));
        }
        return out;
    }

    // Visible → gated by the open range; not visible → allowed only within wallsRange (0 = off).
    private boolean inReach(Level level, LocalPlayer p, Vec3 eye, BlockPos pos, Vec3 center,
                            double openRange, double wallsRange, double distSq) {
        boolean visible = canSee(level, p, eye, pos, center);
        if (visible) return distSq <= openRange * openRange;
        return wallsRange > 0.0 && distSq <= wallsRange * wallsRange;
    }

    // OUTLINE (the shape vanilla block-picking uses), not COLLIDER: with COLLIDER a no-collision block
    // (grass, flowers, snow layers) can never be "hit" by its own raycast, so it was permanently invisible
    // and filtered out at the default wallsRange of 0.
    private boolean canSee(Level level, LocalPlayer p, Vec3 eye, BlockPos pos, Vec3 center) {
        HitResult hit = level.clip(new ClipContext(eye, center,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit == null || hit.getType() == HitResult.Type.MISS) return true;
        if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) return true;
        return hit.getLocation().distanceToSqr(center) < 0.1;
    }

    // One-action paths (interact / instant packet mine): complete immediately, safe to burst per tick.
    private void breakInstant(Minecraft mc, LocalPlayer p, BlockPos pos) {
        Direction face = faceToward(p.getEyePosition(), pos);
        rotateTo(mc, p, pos);
        boolean swing = bool("swing");

        if (bool("interact")) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            if (swing) p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        var conn = mc.getConnection();
        if (conn != null) {
            conn.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
            if (swing) p.swing(InteractionHand.MAIN_HAND);
            conn.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
        }
    }

    // Legit progressive mining: one block at a time, continueDestroyBlock EVERY tick until it breaks —
    // progress decays if the stream stops, and starting a second block cancels the first.
    private void mineLegit(Minecraft mc, LocalPlayer p, BlockPos pos) {
        Direction face = faceToward(p.getEyePosition(), pos);
        rotateTo(mc, p, pos);
        if (!pos.equals(lastMiningPos)) {
            mc.gameMode.startDestroyBlock(pos, face);
            lastMiningPos = pos.immutable();
        } else {
            mc.gameMode.continueDestroyBlock(pos, face);
        }
        if (bool("swing")) p.swing(InteractionHand.MAIN_HAND);
    }

    private void rotateTo(Minecraft mc, LocalPlayer p, BlockPos pos) {
        if (!bool("rotate")) return;
        Vec3 c = Vec3.atCenterOf(pos);
        if (bool("silentRotation")) sendSilentLook(mc, p, c);
        else Util.face(p, c);
    }

    private void sendSilentLook(Minecraft mc, LocalPlayer p, Vec3 point) {
        if (mc.getConnection() == null) return;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation look = AutismRotationUtil.normalizeToSensitivity(
            AutismRotationUtil.lookingAt(point, p.getEyePosition()), cur);
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
            look.yaw(), look.pitch(), p.onGround(), p.horizontalCollision));
    }

    private static Direction faceToward(Vec3 eye, BlockPos pos) {
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Set<String> blockIds(List<String> entries) {
        Set<String> ids = new java.util.HashSet<>();
        if (entries == null) return ids;
        for (String e : entries) {
            if (e == null) continue;
            String v = e.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) ids.add(v);
        }
        return ids;
    }

    private static boolean matchesBlock(Block block, Set<String> ids) {
        if (ids.isEmpty()) return false;
        String id = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
        String shortId = id.substring(id.indexOf(':') + 1);
        return ids.contains(id) || ids.contains(shortId);
    }

    // ---- render -------------------------------------------------------------------------------------

    private void onRender(LevelRenderContext context) {
        if (!isEnabled() || !bool("render")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<BlockPos> targets = renderTargets;
        boolean drawBox = bool("renderBox") && "Cube".equals(choice("shape"));
        if (targets.isEmpty() && !drawBox) return;

        int breakColor = integer("breakColor");
        int boxColor = integer("boxColor");
        float width = (float) decimal("lineWidth");
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        final double cx = cam.x, cy = cam.y, cz = cam.z;
        BlockPos center = mc.player.blockPosition();

        context.submitNodeCollector().submitCustomGeometry(context.poseStack(),
            AutismRenderTypes.tracerEspLines(),
            (pose, vc) -> {
                for (BlockPos bp : targets) {
                    drawBoxLines(pose, vc, bp.getX() - cx, bp.getY() - cy, bp.getZ() - cz,
                        bp.getX() + 1 - cx, bp.getY() + 1 - cy, bp.getZ() + 1 - cz, breakColor, width);
                }
                if (drawBox) {
                    double minX = center.getX() - integer("rangeLeft") - cx;
                    double minY = center.getY() - integer("rangeDown") - cy;
                    double minZ = center.getZ() - integer("rangeBack") - cz;
                    double maxX = center.getX() + integer("rangeRight") + 1 - cx;
                    double maxY = center.getY() + integer("rangeUp") + 1 - cy;
                    double maxZ = center.getZ() + integer("rangeForward") + 1 - cz;
                    drawBoxLines(pose, vc, minX, minY, minZ, maxX, maxY, maxZ, boxColor, width);
                }
            });
    }

    // Wireframe box: 12 edges. AutismWorldGeometry exposes only line(), matching TrajectoryModule's approach.
    private static void drawBoxLines(com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                     com.mojang.blaze3d.vertex.VertexConsumer vc,
                                     double x1, double y1, double z1, double x2, double y2, double z2,
                                     int color, float width) {
        // bottom rectangle
        AutismWorldGeometry.line(pose, vc, x1, y1, z1, x2, y1, z1, color, width);
        AutismWorldGeometry.line(pose, vc, x2, y1, z1, x2, y1, z2, color, width);
        AutismWorldGeometry.line(pose, vc, x2, y1, z2, x1, y1, z2, color, width);
        AutismWorldGeometry.line(pose, vc, x1, y1, z2, x1, y1, z1, color, width);
        // top rectangle
        AutismWorldGeometry.line(pose, vc, x1, y2, z1, x2, y2, z1, color, width);
        AutismWorldGeometry.line(pose, vc, x2, y2, z1, x2, y2, z2, color, width);
        AutismWorldGeometry.line(pose, vc, x2, y2, z2, x1, y2, z2, color, width);
        AutismWorldGeometry.line(pose, vc, x1, y2, z2, x1, y2, z1, color, width);
        // vertical edges
        AutismWorldGeometry.line(pose, vc, x1, y1, z1, x1, y2, z1, color, width);
        AutismWorldGeometry.line(pose, vc, x2, y1, z1, x2, y2, z1, color, width);
        AutismWorldGeometry.line(pose, vc, x2, y1, z2, x2, y2, z2, color, width);
        AutismWorldGeometry.line(pose, vc, x1, y1, z2, x1, y2, z2, color, width);
    }
}
