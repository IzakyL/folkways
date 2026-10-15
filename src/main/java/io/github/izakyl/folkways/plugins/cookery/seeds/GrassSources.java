package io.github.izakyl.folkways.plugins.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.VegetationPlacements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Real grass loot and bonemeal growth, restricted to ground explicitly assigned for seed gathering. */
public final class GrassSources {
    // GrassBlock's 128 attempts make at most 127 / 16 = 7 one-cell random-walk steps.
    public static final int NATIVE_RADIUS = 7;

    private GrassSources() {
    }

    public static Outcome gather(ServerLevel level, Worker who, BlockPos grass, Set<BlockPos> allowedGround) {
        if (!allowedGround.contains(grass.below())) return Outcome.failed(SeedRefusal.AREA_CHANGED);
        if (!who.body().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)) {
            return Outcome.failed(SeedRefusal.NO_STRAW_HAT);
        }
        if (!level.isLoaded(grass) || !level.getBlockState(grass).is(Blocks.SHORT_GRASS)) {
            return Outcome.failed(SeedRefusal.NO_GRASS);
        }
        BlockState before = level.getBlockState(grass);
        List<ItemStack> items = Block.getDrops(before, level, grass, level.getBlockEntity(grass), who.body(), ItemStack.EMPTY);
        try {
            if (!level.removeBlock(grass, false)) return Outcome.failed(SeedRefusal.NO_GRASS);
        } catch (RuntimeException | LinkageError broken) {
            if (level.getBlockState(grass).equals(before)) throw broken;
            org.slf4j.LoggerFactory.getLogger("folkways-cookery").warn("grass {} removed before notification failed", grass, broken);
        }
        return new Outcome.Done(List.of(WorkNoise.broke(grass, before)), items, Optional.empty());
    }

    public static boolean safeRestore(ServerLevel level, BlockPos ground, Set<BlockPos> allowedGround) {
        var grass = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE)
            .getHolder(VegetationPlacements.GRASS_BONEMEAL);
        if (grass.isEmpty() || !boundedFeature(grass.get().value())) return false;
        Set<Object> checkedBiomes = new HashSet<>();
        for (int dx = -NATIVE_RADIUS; dx <= NATIVE_RADIUS; dx++) {
            for (int dz = -NATIVE_RADIUS; dz <= NATIVE_RADIUS; dz++) {
                BlockPos candidate = ground.offset(dx, 0, dz);
                if (!allowedGround.contains(candidate) || !level.isLoaded(candidate.below())
                        || !level.isLoaded(candidate.above(2)) || !level.getBlockState(candidate).is(Blocks.GRASS_BLOCK)
                        || level.getBlockState(candidate.below()).is(Blocks.GRASS_BLOCK)
                        || level.getBlockState(candidate.above()).is(Blocks.GRASS_BLOCK)) return false;
                var biome = level.getBiome(candidate.above());
                if (checkedBiomes.add(biome)) {
                    var flowers = biome.value().getGenerationSettings().getFlowerFeatures();
                    if (!flowers.isEmpty() && (!(flowers.getFirst().config() instanceof RandomPatchConfiguration patch)
                            || !boundedFeature(patch.feature().value()))) return false;
                }
            }
        }
        // Flat grass above non-grass soil forces every valid native random walk to stay at this Y.
        // SimpleBlock plus predicate-only placement writes only its origin (or the upper half of a plant).
        return true;
    }

    private static boolean boundedFeature(PlacedFeature feature) {
        return feature.feature().value().feature() == Feature.SIMPLE_BLOCK
            && feature.feature().value().config() instanceof SimpleBlockConfiguration
            && feature.placement().stream().allMatch(modifier -> modifier instanceof BlockPredicateFilter);
    }

    public static Outcome restore(ServerLevel level, Worker who, BlockPos ground, Set<BlockPos> allowedGround) {
        if (!safeRestore(level, ground, allowedGround)) return Outcome.failed(SeedRefusal.UNSAFE_GROWTH_AREA);
        List<ItemStack> supplied = who.supplied().stream().map(ItemStack::copy).toList();
        ItemStack meal = supplied.stream().filter(stack -> stack.is(Items.BONE_MEAL)).findFirst().orElse(ItemStack.EMPTY);
        if (meal.getCount() != 1) return Outcome.failed(SeedRefusal.NO_GRASS);
        List<BlockPos> possible = new ArrayList<>();
        for (int dx = -NATIVE_RADIUS; dx <= NATIVE_RADIUS; dx++) for (int dz = -NATIVE_RADIUS; dz <= NATIVE_RADIUS; dz++) {
            possible.add(ground.offset(dx, 1, dz));
            possible.add(ground.offset(dx, 2, dz));
        }
        Map<BlockPos, BlockState> before = new java.util.LinkedHashMap<>();
        possible.forEach(cell -> before.put(cell, level.getBlockState(cell)));
        boolean applied;
        try {
            applied = BoneMealItem.applyBonemeal(meal, level, ground, null);
        } catch (RuntimeException | LinkageError broken) {
            if (before.entrySet().stream().noneMatch(entry -> !level.getBlockState(entry.getKey()).equals(entry.getValue()))) throw broken;
            if (!meal.isEmpty()) meal.shrink(1);
            applied = true;
            org.slf4j.LoggerFactory.getLogger("folkways-cookery").warn("grass grew before bonemeal notification failed", broken);
        }
        List<WorkNoise> noises = applied ? List.of((world, emitter) -> world.levelEvent(1505, ground, 0)) : List.of();
        return new Outcome.Done(noises, List.of(), Optional.empty(), supplied.stream().filter(stack -> !stack.isEmpty()).toList());
    }
}
