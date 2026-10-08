package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;

public final class Costs {

    @FunctionalInterface
    public interface Rule {

        Optional<List<Need>> of(Footprint print);
    }

    private static final List<IntegerProperty> COUNTED = List.of(BlockStateProperties.CANDLES,
        BlockStateProperties.PICKLES, BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT,
        BlockStateProperties.LAYERS);

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();

    private Costs() {
    }

    public static void register(Rule rule) {
        RULES.addFirst(rule);
    }

    public static Optional<List<Need>> of(Footprint print) {
        for (Rule rule : RULES) {
            Optional<List<Need>> found = rule.of(print);
            if (found.isPresent()) {
                return found;
            }
        }
        BlockState anchor = print.cells().get(print.anchor());
        if (anchor.isAir()) {
            return Optional.of(List.of());
        }
        if (anchor.getBlock() instanceof LiquidBlock) {
            return bucketOf(anchor.getFluidState()).map(bucket -> List.of(new Need(bucket, 1)));
        }
        if (anchor.getBlock() instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            return Optional.of(List.of(new Need(ItemSpec.of(key(Items.FLOWER_POT)), 1),
                new Need(ItemSpec.of(key(pot.getPotted().asItem())), 1)));
        }
        Optional<Footprints.Column> column = Footprints.Column.of(anchor.getBlock());
        if (column.isPresent()) {
            Item tip = column.get().tip().asItem();
            return tip == Items.AIR ? Optional.empty()
                : Optional.of(List.of(new Need(ItemSpec.of(key(tip)), print.cells().size())));
        }
        if (anchor.is(Blocks.TALL_SEAGRASS)) {
            return Optional.of(List.of(new Need(ItemSpec.of(key(Items.SEAGRASS)), 1),
                new Need(ItemSpec.of(key(Items.BONE_MEAL)), 1)));
        }
        Item item = anchor.getBlock().asItem();
        if (item == Items.AIR) {
            return Optional.empty();
        }
        return Optional.of(List.of(new Need(ItemSpec.of(key(item)), count(anchor))));
    }

    private static int count(BlockState state) {
        if (state.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            return state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE ? 1 : 2;
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
        }
        for (IntegerProperty counted : COUNTED) {
            if (state.hasProperty(counted)) {
                return state.getValue(counted);
            }
        }
        Block block = state.getBlock();
        if (block instanceof MultifaceBlock) {
            int faces = 0;
            for (Direction direction : Direction.values()) {
                if (MultifaceBlock.hasFace(state, direction)) {
                    faces++;
                }
            }
            return Math.max(faces, 1);
        }
        return 1;
    }

    private static Optional<ItemSpec> bucketOf(FluidState fluid) {
        if (fluid.isEmpty() || !fluid.isSource()) {
            return Optional.empty();
        }
        Item bucket = fluid.getType().getBucket();
        return bucket == Items.AIR ? Optional.empty() : Optional.of(ItemSpec.of(key(bucket)));
    }

    private static ResourceLocation key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }
}
