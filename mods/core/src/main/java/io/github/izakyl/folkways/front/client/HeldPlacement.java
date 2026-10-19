package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.FolkwaysClientConfig;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.api.ui.Placing;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The shape held with the book to be set down whole: it stands on the face aimed at, its footprint centred on the
 * cell there, and goes where the crosshair goes until a click puts it down. With the modifier held, or nothing
 * aimed at, it floats out along the crosshair at a reach the wheel pushes and pulls, as Create's schematics do.
 */
final class HeldPlacement {

    static final Placing.Hand HAND = new Placing.Hand() {
        @Override
        public void hold(Placing.Held held) {
            HeldPlacement.hold(held);
        }

        @Override
        public void drop() {
            HeldPlacement.clear();
        }

        @Override
        public boolean holding() {
            return held != null;
        }
    };

    private static final float[] FRAME = {0.45F, 0.85F, 1.00F};
    private static final Mirror[] MIRRORS = {Mirror.NONE, Mirror.LEFT_RIGHT, Mirror.FRONT_BACK};
    private static final int MIN_REACH = 1;
    private static final int MAX_REACH = 100;
    private static final int DEFAULT_REACH = 12;

    private static final GhostRenderer GHOSTS = new GhostRenderer();

    private static Placing.Held held;
    private static Rotation rotation = Rotation.NONE;
    private static Mirror mirror = Mirror.NONE;
    private static List<Ghost> blocks = List.of();
    private static BlockPos size = BlockPos.ZERO;
    private static BlockPos corner;
    private static int reach = DEFAULT_REACH;

    private HeldPlacement() {
    }

    private static void hold(Placing.Held taken) {
        clear();
        held = taken;
        turned();
        Draft.say(Component.translatable("folkways.placing.held", taken.name()));
    }

    static boolean holding() {
        return held != null;
    }

    static Optional<Component> name() {
        return Optional.ofNullable(held).map(Placing.Held::name);
    }

    static int reach() {
        return reach;
    }

    static void clear() {
        held = null;
        rotation = Rotation.NONE;
        mirror = Mirror.NONE;
        blocks = List.of();
        size = BlockPos.ZERO;
        corner = null;
        GHOSTS.clear();
    }

    static void discard() {
        if (held != null) {
            clear();
            Draft.say(Component.translatable("folkways.placing.dropped"));
        }
    }

    // The blocks as they stand turned and flipped now, and the box they make.
    private static void turned() {
        List<Ghost> all = held.blocks(rotation, mirror);
        blocks = all.stream().filter(ghost -> !ghost.state().isAir()).toList();
        int x = 0;
        int y = 0;
        int z = 0;
        for (Ghost ghost : all) {
            x = Math.max(x, ghost.pos().getX() + 1);
            y = Math.max(y, ghost.pos().getY() + 1);
            z = Math.max(z, ghost.pos().getZ() + 1);
        }
        size = new BlockPos(x, y, z);
        corner = null;
    }

    static void tick() {
        if (held == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null || HeldBook.bound(player).isEmpty()) {
            clear();
            return;
        }
        BlockPos aimed = cornerAimed(player);
        corner = aimed;
        BlockPos at = corner;
        GHOSTS.show(level, blocks.stream().map(ghost -> new Ghost(ghost.pos().offset(at), ghost.state())).toList());
    }

    // The cell beside the face aimed at is where its floor stands, in the middle of its footprint; held out in the
    // air, it is the cell at the reach along the crosshair.
    private static BlockPos cornerAimed(Player player) {
        Aim aim = Aim.of(player);
        Optional<BlockHitResult> hit = FolkwaysClientGameEvents.modifierDown() ? Optional.empty() : aim.block(player);
        BlockPos floor = hit.map(found -> found.getBlockPos().relative(found.getDirection()))
            .orElseGet(() -> BlockPos.containing(aim.at(reach)));
        return floor.offset(-size.getX() / 2, 0, -size.getZ() / 2);
    }

    static boolean handleLeftClick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (held == null || minecraft.player == null) {
            return false;
        }
        if (FolkwaysClientGameEvents.sneakDown(minecraft.player)) {
            discard();
            return true;
        }
        if (corner != null) {
            held.place(corner, rotation, mirror);
        }
        return true;
    }

    /**
     * With the modifier the wheel pushes it out or pulls it in, with the other key it turns it a quarter, and with
     * both it flips it the next way.
     */
    static boolean handleScroll(double delta, boolean modifier, boolean alternate) {
        if (held == null || !(modifier || alternate)) {
            return false;
        }
        if (modifier && !alternate) {
            reach = Mth.clamp(reach + FolkwaysClientConfig.away(delta > 0 ? 1 : -1), MIN_REACH, MAX_REACH);
            Draft.say(Component.translatable("folkways.zone.reach", reach));
            return true;
        }
        int by = delta > 0 ? 1 : -1;
        if (alternate && !modifier) {
            turn(by);
        } else {
            flip(by);
        }
        return true;
    }

    /** A quarter turn, clockwise for a positive step. */
    static void turn(int by) {
        if (held == null) {
            return;
        }
        rotation = rotation.getRotated(by > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
        turned();
    }

    /** The next way of flipping it: none, left to right, front to back. */
    static void flip(int by) {
        if (held == null) {
            return;
        }
        int at = 0;
        while (MIRRORS[at] != mirror) {
            at++;
        }
        mirror = MIRRORS[Math.floorMod(at + by, MIRRORS.length)];
        turned();
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || held == null
            || corner == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        GHOSTS.drawModels(level, camera, partialTick);
        GHOSTS.drawFills(level, poseStack, buffers.getBuffer(RenderType.debugFilledBox()), partialTick);
        buffers.endBatch(RenderType.debugFilledBox());
        GHOSTS.drawOutlines(level, poseStack, buffers.getBuffer(RenderType.lines()), partialTick);
        LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()),
            AABB.encapsulatingFullBlocks(corner, corner.offset(size).offset(-1, -1, -1)).inflate(0.03D),
            FRAME[0], FRAME[1], FRAME[2], 1.0F);
        buffers.endBatch(RenderType.lines());

        poseStack.popPose();
    }
}
