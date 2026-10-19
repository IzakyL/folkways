package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Stat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The trade's independent abilities; no ability is inferred from another perk's rank. */
public final class BuildingPerks {
    public static final TagKey<Block> ORDINARY_STONE = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "prospecting_stone"));
    public static final TagKey<Block> RETURNABLE_MATERIAL = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "returnable_building_materials"));
    public static final Stat PROSPECTING = stat("building_prospecting", 0, 1);
    public static final Stat SECOND_FIND = stat("building_second_find", 0, 1);
    public static final Stat MATERIAL_RETURN = stat("building_material_return", 0, 1);
    public static final Stat QUARRY_MOMENTUM = stat("building_quarry_momentum", 0, 2);
    private record Streak(BlockState stone, long tick, int count) { }
    private static final Map<Mob, Streak> STREAKS = new WeakHashMap<>();

    private BuildingPerks() { }

    private static Stat stat(String id, double base, double max) {
        return new Stat(ResourceLocation.fromNamespaceAndPath("folkways", id), base, 0, max);
    }

    public static boolean ordinaryStone(BlockState state) {
        return state.is(ORDINARY_STONE);
    }

    public static double momentum(Worker worker) {
        Streak streak = STREAKS.get(worker.body());
        if (streak == null || worker.body().level().getGameTime() - streak.tick() > 200) return 1;
        return 1 + worker.effects().value(QUARRY_MOMENTUM) * Math.min(1, streak.count() / 8.0);
    }

    // Validate the next target before its wind-up: another material never borrows the previous stone's bonus.
    public static double momentum(Worker worker, BlockState target) {
        Streak streak = STREAKS.get(worker.body());
        if (!ordinaryStone(target) || streak != null && !streak.stone().equals(target)) {
            interrupt(worker);
            return 1;
        }
        return momentum(worker);
    }

    public static double constructionHaste(Worker worker) {
        interrupt(worker);
        return BuildContent.haste(worker);
    }

    public static void mined(Worker worker, BlockState stone) {
        if (!ordinaryStone(stone)) { STREAKS.remove(worker.body()); return; }
        long tick = worker.body().level().getGameTime();
        Streak previous = STREAKS.get(worker.body());
        int count = previous != null && previous.stone().equals(stone) && tick - previous.tick() <= 200
            ? Math.min(8, previous.count() + 1) : 1;
        STREAKS.put(worker.body(), new Streak(stone, tick, count));
    }

    public static void interrupt(Worker worker) { STREAKS.remove(worker.body()); }

    public static List<ItemStack> refunded(Worker worker, List<ItemStack> drawn, BlockState goal) {
        List<ItemStack> refunds = new ArrayList<>();
        if (!goal.is(RETURNABLE_MATERIAL) || goal.hasBlockEntity() || goal.getBlock() instanceof EntityBlock) return refunds;
        double chance = worker.effects().value(MATERIAL_RETURN);
        for (ItemStack stack : drawn) {
            if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() != goal.getBlock()) continue;
            int count = 0;
            for (int i = 0; i < stack.getCount(); i++) if (worker.body().getRandom().nextDouble() < chance) count++;
            if (count > 0) refunds.add(stack.copyWithCount(count));
        }
        return refunds;
    }

    public static List<ItemStack> discoveries(ServerLevel level, Worker worker, BlockPos pos, BlockState stone) {
        if (!ordinaryStone(stone)
                || worker.body().getRandom().nextDouble() >= worker.effects().value(PROSPECTING)) return List.of();
        var ore = LocalMinerals.choose(level, pos, stone, worker.body().getRandom());
        if (ore.isEmpty()) return List.of();
        // Ordinary loot intentionally uses no fortune or silk touch: this is a mineral find, not another tool use.
        List<ItemStack> drops = new ArrayList<>(net.minecraft.world.level.block.Block.getDrops(
            ore.get(), level, pos, null, worker.body(), ItemStack.EMPTY));
        if (worker.body().getRandom().nextDouble() < worker.effects().value(SECOND_FIND)) {
            drops.addAll(net.minecraft.world.level.block.Block.getDrops(ore.get(), level, pos, null, worker.body(), ItemStack.EMPTY));
        }
        return drops;
    }
}
