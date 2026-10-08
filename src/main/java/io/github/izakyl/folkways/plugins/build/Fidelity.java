package io.github.izakyl.folkways.plugins.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

public final class Fidelity {

    @FunctionalInterface
    public interface Rule {

        boolean keeps(Block block, Property<?> property);
    }

    private static final Set<Property<?>> PLACED = Set.of(
        BlockStateProperties.FACING,
        BlockStateProperties.HORIZONTAL_FACING,
        BlockStateProperties.FACING_HOPPER,
        BlockStateProperties.VERTICAL_DIRECTION,
        BlockStateProperties.AXIS,
        BlockStateProperties.HORIZONTAL_AXIS,
        BlockStateProperties.HALF,
        BlockStateProperties.DOUBLE_BLOCK_HALF,
        BlockStateProperties.BED_PART,
        BlockStateProperties.DOOR_HINGE,
        BlockStateProperties.SLAB_TYPE,
        BlockStateProperties.CHEST_TYPE,
        BlockStateProperties.ATTACH_FACE,
        BlockStateProperties.ROTATION_16,
        BlockStateProperties.HANGING,
        BlockStateProperties.BELL_ATTACHMENT,
        BlockStateProperties.ORIENTATION,
        BlockStateProperties.CANDLES,
        BlockStateProperties.PICKLES,
        BlockStateProperties.EGGS,
        BlockStateProperties.FLOWER_AMOUNT,
        BlockStateProperties.LAYERS,
        BlockStateProperties.WATERLOGGED);

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>(List.of(
        (block, property) -> PLACED.contains(property),
        (block, property) -> property.getValueClass() == Direction.class
            || property.getValueClass() == Direction.Axis.class,
        (block, property) -> (block instanceof MultifaceBlock || block instanceof VineBlock)
            && PipeBlock.PROPERTY_BY_DIRECTION.containsValue(property)));

    private static final Map<Block, List<Property<?>>> LOOSE = new ConcurrentHashMap<>();

    private Fidelity() {
    }

    public static void register(Rule rule) {
        RULES.add(rule);
        LOOSE.clear();
    }

    public static BlockState essence(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return state;
        }
        BlockState plain = state;
        BlockState base = state.getBlock().defaultBlockState();
        for (Property<?> property : loose(state.getBlock())) {
            plain = copy(plain, base, property);
        }
        return plain;
    }

    public static boolean same(BlockState a, BlockState b) {
        return a.is(b.getBlock()) && essence(a) == essence(b);
    }

    static BlockState laid(BlockState state) {
        BlockState plain = essence(state);
        return plain.hasProperty(LeavesBlock.PERSISTENT) ? plain.setValue(LeavesBlock.PERSISTENT, true) : plain;
    }

    static BlockState shaped(BlockState shaped, BlockState laid) {
        if (!shaped.is(laid.getBlock())) {
            return laid;
        }
        List<Property<?>> loose = loose(laid.getBlock());
        BlockState kept = shaped;
        for (Property<?> property : laid.getProperties()) {
            if (!loose.contains(property)) {
                kept = copy(kept, laid, property);
            }
        }
        return kept;
    }

    private static List<Property<?>> loose(Block block) {
        return LOOSE.computeIfAbsent(block, of -> {
            List<Property<?>> loose = new ArrayList<>();
            for (Property<?> property : of.getStateDefinition().getProperties()) {
                if (RULES.stream().noneMatch(rule -> rule.keeps(of, property))) {
                    loose.add(property);
                }
            }
            return List.copyOf(loose);
        });
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState into, BlockState from, Property<T> property) {
        return into.setValue(property, from.getValue(property));
    }
}
