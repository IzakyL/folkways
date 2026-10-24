package io.github.izakyl.folkways.war;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Every War edit is checked here at execution, with no founder or resident identity. */
public final class WarEdits {
    static final TagKey<net.minecraft.world.level.block.Block> PROTECTED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "raid_protected"));
    private static final GameProfile GRIEFER = new GameProfile(UUID.fromString("67a19e80-a20c-4bbd-89dd-5a9d1286cb00"), "[FolkwaysRaid]");
    private WarEdits() { }

    static boolean permits(ServerLevel level, BlockPos at, Mob mob, boolean container, double hardness) {
        if (!level.isLoaded(at) || !level.getWorldBorder().isWithinBounds(at)
                || !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) { return false; }
        var state = level.getBlockState(at);
        float strength = state.getDestroySpeed(level, at);
        if (state.is(PROTECTED) || strength < 0 || strength > hardness || !container && state.hasBlockEntity()) { return false; }
        if (!CommonHooks.canEntityDestroy(level, at, mob)) { return false; }
        return !NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, at, state, FakePlayerFactory.get(level, GRIEFER))).isCanceled();
    }

    static boolean reserve(Mob mob, BlockPos at, boolean explosion) {
        var director = RaidMembers.director(mob).filter(d -> d.owns(mob) && !d.raid().paused);
        if (director.isEmpty() || !(mob.level() instanceof ServerLevel level)) { return false; }
        var raid = director.get().raid();
        int limit = Math.min(WarConfig.BREAKS.get(), raid.definition.limits().brokenBlocks());
        double hardness = Math.min(WarConfig.HARDNESS.get(), raid.definition.limits().hardness());
        if (raid.broken >= limit || !permits(level, at, mob, explosion, hardness) || level.getBlockState(at).isAir()) { return false; }
        raid.broken++; director.get().save();
        return true;
    }

    public static boolean breakBlock(Mob mob, BlockPos at) {
        if (mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at)) > 9 || !reserve(mob, at, false)) { return false; }
        return mob.level().destroyBlock(at, true, mob);
    }
}
