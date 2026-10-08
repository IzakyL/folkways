package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

record Crop(Block block, Set<Block> standing, Item seed, ItemSpec seedSpec, Substrate substrate,
            Habit habit) {

    enum Substrate {
        FARMLAND,
        NATURAL
    }

    private static final Habit TREE = new Habit.Tree(2, 9);

    private record Shape(Substrate substrate, Habit habit, Set<Block> standing) {
    }

    private static final Map<Block, Shape> NAMED = Map.of(
        Blocks.SUGAR_CANE,
        new Shape(Substrate.NATURAL, new Habit.Cane(1, 3), Set.of(Blocks.SUGAR_CANE)),
        Blocks.BAMBOO,
        new Shape(Substrate.NATURAL, new Habit.Cane(1, 4), Set.of(Blocks.BAMBOO)),
        Blocks.MELON_STEM,
        new Shape(Substrate.FARMLAND, new Habit.Stem(Blocks.MELON),
            Set.of(Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM)),
        Blocks.PUMPKIN_STEM,
        new Shape(Substrate.FARMLAND, new Habit.Stem(Blocks.PUMPKIN),
            Set.of(Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM)));

    private static volatile List<ResourceLocation> sowable;

    static List<ResourceLocation> sowable() {
        List<ResourceLocation> known = sowable;
        if (known == null) {
            List<ResourceLocation> found = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof CropBlock || block instanceof SaplingBlock || NAMED.containsKey(block)) {
                    found.add(BuiltInRegistries.BLOCK.getKey(block));
                }
            }
            found.sort(Comparator.comparing(ResourceLocation::toString));
            known = List.copyOf(found);
            sowable = known;
        }
        return known;
    }

    static Optional<Crop> of(LevelReader level, BlockPos where, ResourceLocation blockId) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        Optional<Shape> shape = shapeOf(block.get());
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        Optional<Item> seed = seedOf(level, where, block.get());
        if (seed.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation seedId = BuiltInRegistries.ITEM.getKey(seed.get());
        return Optional.of(new Crop(block.get(), shape.get().standing(), seed.get(),
            ItemSpec.of(seedId), shape.get().substrate(), shape.get().habit()));
    }

    private static Optional<Shape> shapeOf(Block block) {
        Shape named = NAMED.get(block);
        if (named != null) {
            return Optional.of(named);
        }
        if (block instanceof SaplingBlock) {
            return Optional.of(new Shape(Substrate.NATURAL, TREE, Set.of(block)));
        }
        if (block instanceof CropBlock) {
            return Optional.of(new Shape(Substrate.FARMLAND, new Habit.Herb(), Set.of(block)));
        }
        return Optional.empty();
    }

    private static Optional<Item> seedOf(LevelReader level, BlockPos where, Block block) {
        ItemStack picked = block.getCloneItemStack(level, where, block.defaultBlockState());
        if (!picked.isEmpty()) {
            return Optional.of(picked.getItem());
        }
        Item asItem = block.asItem();
        return asItem == null || asItem == net.minecraft.world.item.Items.AIR
            ? Optional.empty()
            : Optional.of(asItem);
    }

    Block fruit() {
        return habit instanceof Habit.Stem stem ? stem.fruit() : block;
    }

    boolean needsFarmland() {
        return substrate == Substrate.FARMLAND;
    }

    boolean grows(BlockState state) {
        for (Block form : standing) {
            if (state.is(form)) {
                return true;
            }
        }
        return false;
    }

    BlockState sown() {
        return block.defaultBlockState();
    }

    List<BlockPos> harvestFrom(LevelReader level, BlockPos ground) {
        return habit.harvest(this, level, ground.above());
    }

    List<BlockPos> canopyOf(LevelReader level, List<BlockPos> cut) {
        return habit.canopy(level, cut);
    }

    boolean sowableAt(LevelReader level, BlockPos ground, Set<BlockPos> sown) {
        BlockPos base = ground.above();
        BlockState standing0 = level.getBlockState(base);
        if (!standing0.isAir() && !standing0.canBeReplaced()) {
            return false;
        }
        return sown().canSurvive(level, base) && habit.hasRoom(level, base, sown);
    }

    boolean roomAt(LevelReader level, BlockPos base) {
        return habit.hasRoom(level, base, Set.of());
    }

    int reach() {
        return habit.reach();
    }

    boolean ripe(LevelReader level, BlockPos base) {
        BlockState state = level.getBlockState(base);
        if (!grows(state)) {
            return false;
        }
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        Optional<IntegerProperty> age = ageOf(state);
        if (age.isPresent()) {
            int most = age.get().getPossibleValues().stream().max(Comparator.naturalOrder()).orElse(0);
            return state.getValue(age.get()) >= most;
        }
        if (block instanceof BonemealableBlock growable) {
            return !growable.isValidBonemealTarget(level, base, state);
        }
        return false;
    }

    boolean cutWhole() {
        return habit.cutWhole();
    }

    boolean cuts(BlockState state) {
        return habit.cuts(this, state);
    }

    private static Optional<IntegerProperty> ageOf(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && "age".equals(integer.getName())) {
                return Optional.of(integer);
            }
        }
        IntegerProperty only = null;
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer) {
                if (only != null) {
                    return Optional.empty();
                }
                only = integer;
            }
        }
        return Optional.ofNullable(only);
    }
}
