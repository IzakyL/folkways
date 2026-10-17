package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** The ray under the crosshair: out of the player's eyes, unless a camera mod draws the crosshair from elsewhere. */
record Aim(Vec3 from, Vec3 look) {
    /** How far the book reaches, which is well past the hand: what it marks is seen, not touched. */
    private static final double RANGE = 64.0D;

    /** How far it reaches from the view from above, whose camera may be a long way off what it looks at. */
    private static final double RANGE_ABOVE = 512.0D;

    private static final boolean SHOULDER_SURFING = ModList.get().isLoaded("shouldersurfing");

    static Aim of(Player player) {
        if (Overhead.on()) {
            return Overhead.aim(Minecraft.getInstance());
        }
        Aim shoulder = SHOULDER_SURFING ? ShoulderAim.of(player) : null;
        return shoulder != null ? shoulder : new Aim(player.getEyePosition(), player.getLookAngle());
    }

    static double range() {
        return Overhead.on() ? RANGE_ABOVE : RANGE;
    }

    Vec3 at(double distance) {
        return from.add(look.scale(distance));
    }

    /**
     * This ray as the ship holding the block stored at {@code storage} sees it: in the level's storage coordinates,
     * where the ship keeps its blocks and the boxes marked on them. On the ground it is the ray itself. A ship
     * only moves and turns what is on it, so distances along the ray stay as they were.
     */
    Aim in(Level level, BlockPos storage) {
        return WorldSpaces.containing(level, storage).map(frame -> {
            Vec3 origin = Vec3.atLowerCornerOf(frame.storageOrigin());
            Vec3 start = frame.toLocal(from).add(origin);
            return new Aim(start, frame.toLocal(from.add(look)).add(origin).subtract(start));
        }).orElse(this);
    }

    /** The block the ray lands on. */
    Optional<BlockHitResult> block(Player player) {
        BlockHitResult hit = player.level().clip(new ClipContext(from, at(range()),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? Optional.of(hit) : Optional.empty();
    }

    /** The entity the ray meets before any block. */
    Optional<EntityHitResult> entity(Player player) {
        // A block hit on a ship lands where the ship keeps its blocks: it is measured from the eye as the ship sees it.
        double reach = block(player).map(hit -> hit.getLocation().distanceTo(in(player.level(), hit.getBlockPos()).from()))
            .orElse(range());
        Vec3 end = at(reach);
        return Optional.ofNullable(ProjectileUtil.getEntityHitResult(player, from, end,
            new AABB(from, end).inflate(1.0D), entity -> !entity.isSpectator() && entity.isPickable(),
            reach * reach));
    }

    static Optional<BlockPos> cell(Player player) {
        return of(player).block(player).map(hit -> hit.getBlockPos().immutable());
    }
}
