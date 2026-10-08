package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

record BuildTarget(Realm realm, Map<BlockPos, BlockState> cells, Set<BlockPos> unloaded, List<Joint> joints,
        BlockPos storageOrigin) {

    BuildTarget(Realm realm, Map<BlockPos, BlockState> cells) {
        this(realm, cells, Set.of(), List.of(), BlockPos.ZERO);
    }

    BuildTarget(Realm realm, Map<BlockPos, BlockState> cells, Set<BlockPos> unloaded, BlockPos storageOrigin) {
        this(realm, cells, unloaded, List.of(), storageOrigin);
    }

    BuildTarget in(ServerLevel level) {
        if (realm instanceof Realm.Dimension) {
            return this;
        }
        BlockPos origin = WorldSpaces
            .frame(level, ((Realm.Frame) realm).structure()).orElseThrow().storageOrigin();
        Map<BlockPos, BlockState> stored = new LinkedHashMap<>();
        cells.forEach((cell, state) -> stored.put(cell.subtract(storageOrigin).offset(origin), state));
        Set<BlockPos> storedUnloaded = new LinkedHashSet<>();
        unloaded.forEach(cell -> storedUnloaded.add(cell.subtract(storageOrigin).offset(origin)));
        List<Joint> storedJoints = joints.stream().map(joint -> joint.moved(origin.subtract(storageOrigin))).toList();
        return new BuildTarget(realm, stored, storedUnloaded, storedJoints, origin);
    }

    BuildTarget {
        cells = Collections.unmodifiableMap(Footprints.complete(cells));
        unloaded = Collections.unmodifiableSet(new LinkedHashSet<>(unloaded));
        joints = List.copyOf(joints);
    }

    static BuildTarget of(BlueprintBuildOrder order, Blueprint blueprint) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            cells.put(block.worldPos(order.anchor().cell()), block.state());
        }
        Set<BlockPos> unloaded = new LinkedHashSet<>();
        for (UnloadedBlock block : blueprint.unloaded()) {
            unloaded.add(order.anchor().cell().offset(block.offset()));
        }
        List<Joint> joints = blueprint.joints().stream().map(joint -> joint.moved(order.anchor().cell())).toList();
        return new BuildTarget(order.anchor().realm(), cells, unloaded, joints, BlockPos.ZERO);
    }

    BlockState wanted(BlockPos pos) {
        BlockState state = cells.get(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : BuildPlanner.wanted(state);
    }

    Footprint footprint(BlockPos pos) {
        return Footprints.of(this::wanted, pos, wanted(pos));
    }

    WorldPos cell(BlockPos pos) {
        return new WorldPos(realm, pos.subtract(storageOrigin));
    }

    boolean occupies(BlockGetter level, BlockPos worldPos) {
        BlockState state = cells.get(worldPos);
        return state != null && !state.isAir()
            && !state.getCollisionShape(level, worldPos).isEmpty();
    }

    boolean clears(BlockPos worldPos) {
        BlockState state = cells.get(worldPos);
        return state != null && state.isAir();
    }
}
