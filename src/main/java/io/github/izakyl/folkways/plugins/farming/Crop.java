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

record Crop(ResourceLocation id, Block block, Set<Block> standing, Item seed, ItemSpec seedSpec,
            Substrate substrate, Habit habit, Optional<CropBehavior> behavior) {

    enum Substrate {
        FARMLAND,
        NATURAL
    }

    private static final Habit TREE = new Habit.Tree(2, 9);

    private static final Habit SQUARE_TREE = new Habit.Tree(2, 9, 2);

    // A sapling that grows alone or four to a square is two crops, the second named as a variant of the first.
    private static final String SQUARE = "/square";

    private static final Set<Block> EITHER_WAY = Set.of(Blocks.JUNGLE_SAPLING, Blocks.SPRUCE_SAPLING);

    private record Shape(Substrate substrate, Habit habit, Set<Block> standing) {
    }

    private static final Map<Block, Shape> NAMED = Map.of(
        Blocks.DARK_OAK_SAPLING,
        new Shape(Substrate.NATURAL, SQUARE_TREE, Set.of(Blocks.DARK_OAK_SAPLING)),
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

    static void forgetSowable() {
        sowable = null;
    }

    static List<ResourceLocation> sowable() {
        List<ResourceLocation> known = sowable;
        if (known == null) {
            List<ResourceLocation> found = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof CropBlock || block instanceof SaplingBlock || NAMED.containsKey(block) || CropBehaviors.of(block).isPresent()) {
                    found.add(BuiltInRegistries.BLOCK.getKey(block));
                }
                if (EITHER_WAY.contains(block)) {
                    found.add(BuiltInRegistries.BLOCK.getKey(block).withSuffix(SQUARE));
                }
            }
            found.sort(Comparator.comparing(ResourceLocation::toString));
            known = List.copyOf(found);
            sowable = known;
        }
        return known;
    }

    static Optional<Crop> of(LevelReader level, BlockPos where, ResourceLocation id) {
        boolean square = id.getPath().endsWith(SQUARE);
        ResourceLocation blockId = square
            ? id.withPath(id.getPath().substring(0, id.getPath().length() - SQUARE.length()))
            : id;
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty() || square && !EITHER_WAY.contains(block.get())) {
            return Optional.empty();
        }
        Optional<CropBehavior> behavior = CropBehaviors.of(block.get());
        Optional<Shape> shape = square
            ? Optional.of(new Shape(Substrate.NATURAL, SQUARE_TREE, Set.of(block.get())))
            : behavior.map(adapted -> new Shape(adapted.needsFarmland() ? Substrate.FARMLAND : Substrate.NATURAL,
                new Habit.Herb(), Set.of(block.get()))).or(() -> shapeOf(block.get()));
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        Optional<Item> seed = behavior.flatMap(adapted -> adapted.seeds().stream().findFirst())
            .or(() -> seedOf(level, where, block.get()));
        if (seed.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation seedId = BuiltInRegistries.ITEM.getKey(seed.get());
        return Optional.of(new Crop(id, block.get(), shape.get().standing(), seed.get(),
            ItemSpec.of(seedId), shape.get().substrate(), shape.get().habit(), behavior));
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

    Crop using(Item item) {
        return new Crop(id, block, standing, item, ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)),
            substrate, habit, behavior);
    }

    boolean plant(net.minecraft.server.level.ServerLevel level,
                  io.github.izakyl.folkways.core.api.work.Worker who, BlockPos base, ItemStack seed) {
        if (behavior.isPresent()) {
            return behavior.get().plant(level, who, base, seed);
        }
        if (!bare(level, base) || !level.setBlock(base, sown(), 3)) {
            return false;
        }
        seed.shrink(1);
        return true;
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
        return behavior.map(CropBehavior::planted).orElseGet(block::defaultBlockState);
    }

    List<BlockPos> harvestFrom(LevelReader level, BlockPos ground) {
        return behavior.map(adapted -> adapted.harvest(level, ground.above()))
            .orElseGet(() -> habit.harvest(this, level, ground.above()));
    }

    List<BlockPos> canopyOf(LevelReader level, List<BlockPos> cut) {
        return behavior.map(adapted -> adapted.attached(level, cut)).orElseGet(() -> habit.canopy(level, cut));
    }

    List<BlockPos> standAt(BlockPos ground) {
        return habit.stand(ground);
    }

    // A stand is sown while any cell of it is bare; the cells already growing are the part of it that is done.
    boolean sowableAt(LevelReader level, List<BlockPos> stand, Set<BlockPos> sown) {
        boolean bare = false;
        for (BlockPos ground : stand) {
            BlockPos base = ground.above();
            if (grows(level.getBlockState(base))) {
                continue;
            }
            if (!bare(level, base)) {
                return false;
            }
            bare = true;
        }
        return bare && habit.hasRoom(level, bases(stand), sown);
    }

    boolean bare(LevelReader level, BlockPos base) {
        if (behavior.isPresent()) {
            return behavior.get().canPlant(level, base);
        }
        BlockState standing0 = level.getBlockState(base);
        return (standing0.isAir() || standing0.canBeReplaced()) && sown().canSurvive(level, base);
    }

    // Bamboo, unlike cane or a sapling, is a stalk nobody walks through: a field of it sown wall to wall shut in
    // whoever stood in it.
    boolean inTheWay(LevelReader level, BlockPos base) {
        return !sown().getCollisionShape(level, base).isEmpty();
    }

    boolean sowsAt(LevelReader level, Plot plot, BlockPos ground) {
        return habit.sowsAt(plot, ground) && !(inTheWay(level, ground.above()) && plot.aisle(ground));
    }

    boolean roomAt(LevelReader level, List<BlockPos> stand) {
        return habit.hasRoom(level, bases(stand), Set.of());
    }

    private static List<BlockPos> bases(List<BlockPos> stand) {
        List<BlockPos> bases = new ArrayList<>(stand.size());
        stand.forEach(ground -> bases.add(ground.above()));
        return bases;
    }

    int reach() {
        return behavior.map(CropBehavior::reach).orElseGet(habit::reach);
    }

    boolean ripe(LevelReader level, BlockPos base) {
        if (behavior.isPresent()) {
            return behavior.get().ripe(level, base);
        }
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
