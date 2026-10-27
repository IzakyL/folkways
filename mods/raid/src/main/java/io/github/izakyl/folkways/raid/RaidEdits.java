package io.github.izakyl.folkways.raid;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Every Raid edit is checked here at execution, with no founder or resident identity. */
public final class RaidEdits {

    static final TagKey<Block> PROTECTED =
        TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("folkways", "raid_protected"));
    private static final GameProfile GRIEFER =
        new GameProfile(UUID.fromString("67a19e80-a20c-4bbd-89dd-5a9d1286cb00"), "[FolkwaysRaid]");

    private record FirePlacement(ServerLevel level, BlockPos at) {
    }

    private static final ThreadLocal<FirePlacement> PLACING_FIRE = new ThreadLocal<>();

    private RaidEdits() {
    }

    static boolean permits(ServerLevel level, BlockPos at, Mob mob, boolean container, double hardness) {
        if (!level.isLoaded(at)
            || !level.getWorldBorder().isWithinBounds(at)
            || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        var state = level.getBlockState(at);
        float strength = state.getDestroySpeed(level, at);
        if (state.is(PROTECTED) || strength < 0 || strength > hardness || !container && state.hasBlockEntity()) {
            return false;
        }
        if (!CommonHooks.canEntityDestroy(level, at, mob)) {
            return false;
        }
        return !NeoForge.EVENT_BUS
            .post(new BlockEvent.BreakEvent(level, at, state, FakePlayerFactory.get(level, GRIEFER)))
            .isCanceled();
    }

    static boolean loot(ServerLevel level, Colony colony, WorldPos store, Mob actor) {
        if (!store.in(level) || !StoreRule.takes(colony.storeRules(store))) {
            return false;
        }
        BlockPos at = store.block(level);
        if (!level.isLoaded(at) || !unlocked(level, at) || !permits(level, at, actor, true, 49)) {
            return false;
        }
        var state = level.getBlockState(at);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = at.relative(ChestBlock.getConnectedDirection(state));
            return unlocked(level, other) && permits(level, other, actor, true, 49);
        }
        return true;
    }

    private static boolean unlocked(ServerLevel level, BlockPos at) {
        // Unknown machine security has no common contract; only lockable containers are eligible.
        if (!level.isLoaded(at)) {
            return false;
        }
        var entity = level.getBlockEntity(at);
        return entity instanceof BaseContainerBlockEntity
            && entity.saveWithoutMetadata(level.registryAccess()).getString("Lock").isEmpty();
    }

    static boolean fireRules(ServerLevel level) {
        return level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK);
    }

    static boolean placeFire(ServerLevel level, BlockPos at, Mob actor) {
        return placeFire(level, at, actor, Blocks.FIRE.defaultBlockState());
    }

    static boolean placeFire(ServerLevel level, BlockPos at, Mob actor, BlockState state) {
        if (!fireRules(level)
            || !level.isLoaded(at)
            || !level.getBlockState(at).isAir()
            || !state.canSurvive(level, at)
            || !permits(level, at, actor, false, 0)) {
            return false;
        }
        return firePlacement(level, at, actor, state);
    }

    static boolean burningFire(ServerLevel level, BlockPos at, Mob actor, BlockState state) {
        if (!fireRules(level) || !permits(level, at, actor, false, 49)) {
            return false;
        }
        return firePlacement(level, at, actor, state);
    }

    private static boolean firePlacement(ServerLevel level, BlockPos at, Mob actor, BlockState state) {
        var snapshot = BlockSnapshot.create(level.dimension(), level, at);
        FirePlacement previous = PLACING_FIRE.get();
        PLACING_FIRE.set(new FirePlacement(level, at.immutable()));
        try {
            if (!level.setBlock(at, state, 3)) {
                return false;
            }
            if (EventHooks.onBlockPlace(actor, snapshot, Direction.UP)) {
                snapshot.restore();
                return false;
            }
            return true;
        } finally {
            if (previous == null) {
                PLACING_FIRE.remove();
            } else {
                PLACING_FIRE.set(previous);
            }
        }
    }

    static boolean placingFire(LevelAccessor level, BlockPos at) {
        FirePlacement placement = PLACING_FIRE.get();
        return placement != null && placement.level() == level && placement.at().equals(at);
    }

    static int burnCost(ServerLevel level, BlockPos at, Mob actor, int remaining) {
        var cells = cells(level, at);
        if (!fireRules(level) || cells.size() > remaining) {
            return 0;
        }
        for (BlockPos cell : cells) {
            if (!permits(level, cell, actor, false, 49)) {
                return 0;
            }
        }
        return cells.size();
    }

    private static List<BlockPos> cells(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        var half = BlockStateProperties.DOUBLE_BLOCK_HALF;
        if (state.hasProperty(half)) {
            BlockPos other = state.getValue(half) == DoubleBlockHalf.LOWER ? at.above() : at.below();
            if (level.getBlockState(other).is(state.getBlock())) {
                return List.of(at.immutable(), other);
            }
        }
        return List.of(at.immutable());
    }

    static boolean reserve(Mob mob, BlockPos at, boolean explosion) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        return reserve(mob, cells(level, at), explosion);
    }

    private static boolean reserve(Mob mob, List<BlockPos> cells, boolean explosion) {
        var director = RaidMembers.director(mob).filter(d -> d.owns(mob) && !d.raid().paused);
        if (director.isEmpty() || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        var raid = director.get().raid();
        int limit = Math.min(RaidConfig.BREAKS.get(), raid.definition.limits().brokenBlocks());
        double hardness = Math.min(RaidConfig.HARDNESS.get(), raid.definition.limits().hardness());
        if (raid.broken + cells.size() > limit) {
            return false;
        }
        for (BlockPos at : cells) {
            if (!permits(level, at, mob, explosion, hardness) || level.getBlockState(at).isAir()) {
                return false;
            }
        }
        raid.broken += cells.size();
        director.get().save();
        return true;
    }

    static void explosion(Mob mob, List<BlockPos> affected) {
        if (!(mob.level() instanceof ServerLevel level)) {
            affected.clear();
            return;
        }
        var checked = new HashSet<BlockPos>();
        var allowed = new LinkedHashSet<BlockPos>();
        for (BlockPos at : List.copyOf(affected)) {
            if (checked.contains(at)) {
                continue;
            }
            var cells = cells(level, at);
            checked.addAll(cells);
            if (reserve(mob, cells, true)) {
                allowed.addAll(cells);
            }
        }
        affected.clear();
        affected.addAll(allowed);
    }

    public static void projectileHit(BlockState state, Level level, BlockHitResult hit, Projectile projectile) {
        if (!(projectile.getOwner() instanceof Mob mob) || !RaidMembers.marked(mob)) {
            state.onProjectileHit(level, state, hit, projectile);
            return;
        }
        // Native block reactions may ignite TNT or change blocks even when the arrow only embeds.
        int cost = level instanceof ServerLevel server ? cells(server, hit.getBlockPos()).size() : 1;
        if (!reserve(mob, hit.getBlockPos(), false)) {
            return;
        }
        state.onProjectileHit(level, state, hit, projectile);
        if (level.getBlockState(hit.getBlockPos()).equals(state)) {
            RaidMembers.director(mob)
                .ifPresent(
                director -> {
                    director.raid().broken -= cost;
                    director.save();
                });
        }
    }

    public static boolean breakBlock(Mob mob, BlockPos at) {
        if (mob.distanceToSqr(Vec3.atCenterOf(at)) > 9 || !reserve(mob, at, false)) {
            return false;
        }
        return mob.level().destroyBlock(at, true, mob);
    }
}
