package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
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
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        for (BlockPos cell : goal.cells().keySet()) {
            Footprints.standing(world, cell).forEach((part, state) -> {
                before.putIfAbsent(part, state);
                after.putIfAbsent(part, BuildPlanner.leftBehind(state));
            });
        }
        after.putAll(goal.cells());
        return new Step(BuildJob.Kind.LAY, goal.anchor(), before, after);
    }

    static Step pour(BlockPos cell, BlockState dry) {
        return new Step(BuildJob.Kind.POUR, cell, Map.of(cell, dry),
            Map.of(cell, dry.setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    static Step strip(BlockGetter world, BlockPos cell) {
        Map<BlockPos, BlockState> standing = Footprints.standing(world, cell);
        Map<BlockPos, BlockState> cleared = new LinkedHashMap<>();
        standing.forEach((part, state) -> cleared.put(part, BuildPlanner.leftBehind(state)));
        return new Step(BuildJob.Kind.STRIP, standing.keySet().iterator().next(), standing, cleared);
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
        List<WorldPos> ends = joint.map(found -> List.of(target.cell(found.from()), target.cell(found.to())))
            .orElse(List.of());
        return new BuildJob(kind, target.cell(focus), footings, addressed(target, before), addressed(target, after),
            ends, cost);
    }

    private static Map<WorldPos, BlockState> addressed(BuildTarget target, Map<BlockPos, BlockState> cells) {
        Map<WorldPos, BlockState> addressed = new LinkedHashMap<>();
        cells.forEach((cell, state) -> addressed.put(target.cell(cell), state));
        return addressed;
    }
}
