package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.plugins.build.mixin.GrowingPlantBlockAccess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.PistonType;

public final class Footprints {

    @FunctionalInterface
    public interface Rule {

        Optional<Footprint> of(Function<BlockPos, BlockState> around, BlockPos cell, BlockState state);
    }

    record Column(Direction growth, Block body, Block tip) {

        static Optional<Column> of(Block block) {
            if (block instanceof GrowingPlantBlock) {
                GrowingPlantBlockAccess plant = (GrowingPlantBlockAccess) block;
                return Optional.of(new Column(plant.folkways$growth(), plant.folkways$body(), plant.folkways$head()));
            }
            if (block == Blocks.BIG_DRIPLEAF || block == Blocks.BIG_DRIPLEAF_STEM) {
                return Optional.of(new Column(Direction.UP, Blocks.BIG_DRIPLEAF_STEM, Blocks.BIG_DRIPLEAF));
            }
            return Optional.empty();
        }

        boolean holds(BlockState state) {
            return state.is(body) || state.is(tip);
        }
    }

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>(List.of(
        (around, cell, state) -> twoHigh(cell, state),
        (around, cell, state) -> bed(cell, state),
        (around, cell, state) -> chest(cell, state),
        (around, cell, state) -> piston(cell, state),
        Footprints::column));

    private static final List<Predicate<BlockState>> UNLAID = new CopyOnWriteArrayList<>(List.of(
        state -> state.getBlock() instanceof PistonHeadBlock || state.getBlock() instanceof MovingPistonBlock,
        state -> state.getBlock() instanceof PitcherCropBlock
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER));

    private Footprints() {
    }

    public static void register(Rule rule) {
        RULES.addFirst(rule);
    }

    public static void unlaid(Predicate<BlockState> part) {
        UNLAID.add(part);
    }

    public static Footprint of(Function<BlockPos, BlockState> around, BlockPos cell, BlockState state) {
        for (Rule rule : RULES) {
            Optional<Footprint> found = rule.of(around, cell, state);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return Footprint.single(cell, state);
    }

    static boolean unlaid(BlockState state) {
        return UNLAID.stream().anyMatch(part -> part.test(state));
    }

    static boolean formed(Footprint print) {
        return Column.of(print.cells().get(print.anchor()).getBlock()).map(column -> {
            List<BlockState> parts = List.copyOf(print.cells().values());
            for (int at = 0; at < parts.size(); at++) {
                if (!parts.get(at).is(at == parts.size() - 1 ? column.tip() : column.body())) {
                    return false;
                }
            }
            return true;
        }).orElse(true);
    }

    static BlockState alone(Footprint print) {
        BlockState anchor = print.cells().get(print.anchor());
        return Column.of(anchor.getBlock()).map(column -> column.tip().withPropertiesOf(anchor)).orElse(anchor);
    }

    static Map<BlockPos, BlockState> standing(BlockGetter world, BlockPos cell) {
        BlockState state = world.getBlockState(cell);
        Map<BlockPos, BlockState> found = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> part : of(world::getBlockState, cell, state).cells().entrySet()) {
            if (part.getKey().equals(cell)) {
                found.put(cell, state);
                continue;
            }
            BlockState there = world.getBlockState(part.getKey());
            if (Fidelity.same(there, part.getValue())) {
                found.put(part.getKey(), there);
            }
        }
        return found;
    }

    static Map<BlockPos, BlockState> complete(Map<BlockPos, BlockState> cells) {
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>();
        cells.forEach((cell, state) -> {
            if (!unlaid(state)) {
                laid.put(cell, state.getBlock() instanceof LiquidBlock ? state : Fidelity.laid(state));
            }
        });
        Map<BlockPos, BlockState> whole = new LinkedHashMap<>();
        Map<BlockPos, BlockState> anchored = new LinkedHashMap<>();
        Function<BlockPos, BlockState> around = pos -> laid.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        laid.forEach((cell, state) -> {
            Footprint print = of(around, cell, state);
            if (print.cells().size() == 1) {
                whole.put(cell, state);
            } else {
                BlockState anchor = laid.get(print.anchor());
                if (anchor == null || anchor == Fidelity.laid(print.cells().get(print.anchor()))) {
                    print.cells().forEach((part, partState) -> anchored.put(part, Fidelity.laid(partState)));
                }
            }
        });
        whole.putAll(anchored);
        return whole;
    }

    private static Optional<Footprint> twoHigh(BlockPos cell, BlockState state) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || state.getBlock() instanceof PitcherCropBlock) {
            return Optional.empty();
        }
        BlockPos lower = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? cell : cell.below();
        return Optional.of(Footprint.pair(
            lower, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
            lower.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
    }

    private static Optional<Footprint> bed(BlockPos cell, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)) {
            return Optional.empty();
        }
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos foot = state.getValue(BedBlock.PART) == BedPart.FOOT ? cell : cell.relative(facing.getOpposite());
        return Optional.of(Footprint.pair(
            foot, state.setValue(BedBlock.PART, BedPart.FOOT),
            foot.relative(facing), state.setValue(BedBlock.PART, BedPart.HEAD)));
    }

    private static Optional<Footprint> chest(BlockPos cell, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return Optional.empty();
        }
        BlockPos other = cell.relative(ChestBlock.getConnectedDirection(state));
        BlockState otherState = state.setValue(ChestBlock.TYPE, state.getValue(ChestBlock.TYPE).getOpposite());
        return Optional.of(state.getValue(ChestBlock.TYPE) == ChestType.LEFT
            ? Footprint.pair(cell, state, other, otherState)
            : Footprint.pair(other, otherState, cell, state));
    }

    private static Optional<Footprint> piston(BlockPos cell, BlockState state) {
        if (state.getBlock() instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.EXTENDED)) {
            Direction facing = state.getValue(PistonBaseBlock.FACING);
            BlockState head = Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(PistonHeadBlock.FACING, facing)
                .setValue(PistonHeadBlock.TYPE, state.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT);
            return Optional.of(Footprint.pair(cell, state, cell.relative(facing), head));
        }
        if (state.getBlock() instanceof PistonHeadBlock) {
            Direction facing = state.getValue(PistonHeadBlock.FACING);
            BlockState base = (state.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY ? Blocks.STICKY_PISTON : Blocks.PISTON)
                .defaultBlockState()
                .setValue(PistonBaseBlock.FACING, facing)
                .setValue(PistonBaseBlock.EXTENDED, true);
            return Optional.of(Footprint.pair(cell.relative(facing.getOpposite()), base, cell, state));
        }
        return Optional.empty();
    }

    private static Optional<Footprint> column(Function<BlockPos, BlockState> around, BlockPos cell, BlockState state) {
        Optional<Column> found = Column.of(state.getBlock());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Column column = found.get();
        Function<BlockPos, BlockState> at = pos -> pos.equals(cell) ? state : around.apply(pos);
        BlockPos root = cell;
        while (column.holds(at.apply(root.relative(column.growth().getOpposite())))) {
            root = root.relative(column.growth().getOpposite());
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (BlockPos part = root; column.holds(at.apply(part)); part = part.relative(column.growth())) {
            cells.put(part, at.apply(part));
        }
        return Optional.of(new Footprint(root, cells));
    }
}
