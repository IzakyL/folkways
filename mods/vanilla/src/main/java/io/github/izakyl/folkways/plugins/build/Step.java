package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

record Step(BuildJob.Kind kind, BlockPos focus, Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after,
        Optional<Joint> joint, List<Need> cost) {

    Step {
        before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
        cost = List.copyOf(cost);
    }

    Step(BuildJob.Kind kind, BlockPos focus, Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after) {
        this(kind, focus, before, after, Optional.empty(), List.of());
    }

    static Step join(BlockGetter world, Joint joint, List<Need> cost) {
        Map<BlockPos, BlockState> ends = new LinkedHashMap<>();
        ends.put(joint.from(), world.getBlockState(joint.from()));
        ends.put(joint.to(), world.getBlockState(joint.to()));
        return new Step(BuildJob.Kind.JOIN, joint.from(), ends, ends, Optional.of(joint), cost);
    }

    static Step lay(BlockGetter world, Footprint goal) {
        return lay(world, goal, cell -> false);
    }

    static Step lay(BlockGetter world, Footprint goal, Predicate<BlockPos> met) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        for (BlockPos cell : goal.cells().keySet()) {
            if (met.test(cell)) {
                continue;
            }
            standing(world, cell, met).forEach((part, state) -> {
                before.putIfAbsent(part, state);
                after.putIfAbsent(part, BuildPlanner.leftBehind(state));
            });
        }
        goal.cells().forEach((cell, state) -> {
            if (!met.test(cell)) {
                after.put(cell, state);
            }
        });
        return new Step(BuildJob.Kind.LAY, after.containsKey(goal.anchor()) ? goal.anchor()
            : after.keySet().stream().findFirst().orElse(goal.anchor()), before, after);
    }

    static Step strip(BlockGetter world, BlockPos cell) {
        return strip(world, cell, pos -> false);
    }

    static Step strip(BlockGetter world, BlockPos cell, Predicate<BlockPos> met) {
        Map<BlockPos, BlockState> standing = standing(world, cell, met);
        Map<BlockPos, BlockState> cleared = new LinkedHashMap<>();
        standing.forEach((part, state) -> cleared.put(part, BuildPlanner.leftBehind(state)));
        return new Step(BuildJob.Kind.STRIP, standing.keySet().iterator().next(), standing, cleared);
    }

    private static Map<BlockPos, BlockState> standing(BlockGetter world, BlockPos cell, Predicate<BlockPos> met) {
        Map<BlockPos, BlockState> standing = new LinkedHashMap<>();
        if (met.test(cell)) {
            return standing;
        }
        BlockState state = world.getBlockState(cell);
        Footprint footprint = Footprints.of(pos -> met.test(pos) ? Blocks.AIR.defaultBlockState()
            : world.getBlockState(pos), cell, state);
        footprint.cells().forEach((part, expected) -> {
            if (!met.test(part)) {
                BlockState actual = part.equals(cell) ? state : world.getBlockState(part);
                if (part.equals(cell) || actual == expected) {
                    standing.put(part, actual);
                }
            }
        });
        return standing;
    }

    /** Water let into a block laid dry where the target wants it waterlogged: one bucket poured into it. */
    static Step soak(BlockPos cell, BlockState wet) {
        return new Step(BuildJob.Kind.SOAK, cell, Map.of(cell, dry(wet)), Map.of(cell, wet), Optional.empty(),
            Costs.poured(Blocks.WATER.defaultBlockState()).orElse(List.of()));
    }

    /** This step with {@code cells} laid dry, the water to be let into them after. */
    Step dried(Set<BlockPos> cells) {
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>(after);
        cells.forEach(cell -> laid.computeIfPresent(cell, (pos, state) -> dry(state)));
        return new Step(kind, focus, before, laid, joint, cost);
    }

    static boolean soaked(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    static BlockState dry(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
            ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
    }

    static Step drain(BlockPos focus, Map<BlockPos, BlockState> layer) {
        Map<BlockPos, BlockState> cleared = new LinkedHashMap<>();
        layer.keySet().forEach(cell -> cleared.put(cell, Blocks.AIR.defaultBlockState()));
        return new Step(BuildJob.Kind.DRAIN, focus, layer, cleared);
    }

    static Step raise(List<BlockPos> column) {
        Map<BlockPos, BlockState> empty = new LinkedHashMap<>();
        Map<BlockPos, BlockState> raised = new LinkedHashMap<>();
        for (BlockPos cell : column) {
            empty.put(cell, Blocks.AIR.defaultBlockState());
            raised.put(cell, ScaffoldNode.state());
        }
        return new Step(BuildJob.Kind.RAISE, column.getFirst(), empty, raised);
    }

    static Step lower(List<BlockPos> column) {
        Map<BlockPos, BlockState> raised = new LinkedHashMap<>();
        Map<BlockPos, BlockState> empty = new LinkedHashMap<>();
        for (BlockPos cell : column) {
            raised.put(cell, ScaffoldNode.state());
            empty.put(cell, Blocks.AIR.defaultBlockState());
        }
        return new Step(BuildJob.Kind.LOWER, column.getFirst(), raised, empty);
    }

    BuildJob job(BuildTarget target, Stances footings) {
        return job(null, target, footings);
    }

    BuildJob job(@Nullable ServerLevel level, BuildTarget target, Stances footings) {
        return job(level, target, footings, Set.of());
    }

    /** The job for this step. A laying step is one shot: it pays for and fires what the target aims at its focus. */
    BuildJob job(@Nullable ServerLevel level, BuildTarget target, Stances footings, Set<BlockPos> met) {
        List<WorldPos> ends = joint.map(found -> List.of(target.cell(found.from()), target.cell(found.to())))
            .orElse(List.of());
        if (kind != BuildJob.Kind.LAY) {
            return new BuildJob(kind, target.cell(focus), footings, addressed(target, before), addressed(target, after),
                ends, cost, Map.of(), Set.of());
        }
        Optional<SchematicPlacement.Launch> launch = target.launch(focus);
        Set<WorldPos> shot = new LinkedHashSet<>();
        Map<WorldPos, BlockData> data = new LinkedHashMap<>();
        launch.ifPresent(found -> found.produces().forEach(cell -> {
            if (after.containsKey(cell)) {
                shot.add(target.cell(cell));
            }
            BlockData payload = target.data().get(cell);
            if (payload != null) {
                data.put(target.cell(cell), payload);
            }
        }));
        List<Need> paid = launch.flatMap(found -> level == null
                ? BuildJob.quoted(found, target::wanted)
                : target.costs(level, found, met))
            .orElse(List.of());
        return new BuildJob(kind, target.cell(focus), footings, addressed(target, before), addressed(target, after),
            ends, paid, data, shot);
    }

    /** The cells of a shot still to be placed. */
    static Optional<Footprint> remaining(Footprint print, Set<BlockPos> met) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        print.cells().forEach((cell, state) -> {
            if (!met.contains(cell)) {
                cells.put(cell, state);
            }
        });
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        BlockPos anchor = cells.containsKey(print.anchor()) ? print.anchor() : cells.keySet().iterator().next();
        return Optional.of(new Footprint(anchor, cells));
    }

    private static Map<WorldPos, BlockState> addressed(BuildTarget target, Map<BlockPos, BlockState> cells) {
        Map<WorldPos, BlockState> addressed = new LinkedHashMap<>();
        cells.forEach((cell, state) -> addressed.put(target.cell(cell), state));
        return addressed;
    }
}
