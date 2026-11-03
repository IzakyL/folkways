package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** What an order builds: the states the schematicannon would print, cell by cell, and the shots that print them. */
record BuildTarget(Realm realm, Map<BlockPos, BlockState> cells, Set<BlockPos> unloaded, List<Joint> joints,
        BlockPos storageOrigin, Map<BlockPos, BlockData> data) {

    BuildTarget(Realm realm, Map<BlockPos, BlockState> cells, Set<BlockPos> unloaded, List<Joint> joints,
            BlockPos storageOrigin) {
        this(realm, cells, unloaded, joints, storageOrigin, Map.of());
    }

    BuildTarget(Realm realm, Map<BlockPos, BlockState> cells) {
        this(realm, cells, Set.of(), List.of(), BlockPos.ZERO);
    }

    BuildTarget(Realm realm, Map<BlockPos, BlockState> cells, Set<BlockPos> unloaded, BlockPos storageOrigin) {
        this(realm, cells, unloaded, List.of(), storageOrigin);
    }

    BuildTarget {
        cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        unloaded = Collections.unmodifiableSet(new LinkedHashSet<>(unloaded));
        joints = List.copyOf(joints);
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
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
        Map<BlockPos, BlockData> storedData = new LinkedHashMap<>();
        data.forEach((cell, payload) -> storedData.put(cell.subtract(storageOrigin).offset(origin), payload));
        return new BuildTarget(realm, stored, storedUnloaded, storedJoints, origin, storedData);
    }

    /** The order's target, from a blueprint already resolved as the schematicannon prints it. */
    static BuildTarget of(BlueprintBuildOrder order, Blueprint blueprint) {
        if (!blueprint.confirmed()) {
            throw new IllegalArgumentException("an order is built from a resolved blueprint");
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        Map<BlockPos, BlockData> data = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            cells.put(block.worldPos(order.anchor().cell()), block.state());
            if (block.data().present()) {
                data.put(block.worldPos(order.anchor().cell()), block.data());
            }
        }
        Set<BlockPos> unloaded = new LinkedHashSet<>();
        for (UnloadedBlock block : blueprint.unloaded()) {
            unloaded.add(order.anchor().cell().offset(block.offset()));
        }
        List<Joint> joints = blueprint.joints().stream().map(joint -> joint.moved(order.anchor().cell())).toList();
        return new BuildTarget(order.anchor().realm(), cells, unloaded, joints, BlockPos.ZERO, data);
    }

    /** The cannon's only check: a cell that already holds the state it would print is skipped. */
    boolean satisfied(LevelReader level, BlockPos pos) {
        return BuildPlanner.satisfied(level.getBlockState(pos), wanted(pos));
    }

    BlockState wanted(BlockPos pos) {
        BlockState state = cells.get(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    BlockData dataAt(BlockPos pos) {
        return data.getOrDefault(pos, BlockData.EMPTY);
    }

    /**
     * The shot aimed at a cell. A fluid source is poured on its own, a step outside the cannon's rules; air is
     * cleared, not shot; a part another shot makes has none of its own.
     */
    Optional<SchematicPlacement.Launch> launch(BlockPos pos) {
        BlockState state = cells.get(pos);
        if (state == null || state.isAir()) {
            return Optional.empty();
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return Optional.of(SchematicPlacement.Launch.of(pos, Set.of(pos)));
        }
        return SchematicPlacement.adapter().launch(this::wanted, pos);
    }

    /** Every shot of the target, by each cell it leaves placed. A cell no shot makes is missing. */
    Map<BlockPos, SchematicPlacement.Launch> shots() {
        Map<BlockPos, SchematicPlacement.Launch> shots = new LinkedHashMap<>();
        for (BlockPos cell : cells.keySet()) {
            launch(cell).ifPresent(launch -> launch.produces().stream().filter(cells::containsKey)
                .forEach(made -> shots.putIfAbsent(made, launch)));
        }
        return shots;
    }

    /** The target cells a shot leaves placed, its own cell first. */
    Footprint footprint(SchematicPlacement.Launch launch) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        placed.put(launch.anchor(), wanted(launch.anchor()));
        launch.produces().stream().filter(cells::containsKey).forEach(cell -> placed.putIfAbsent(cell, wanted(cell)));
        return new Footprint(launch.anchor(), placed);
    }

    /** What a shot costs, leaving out what was paid already; nothing when the colony cannot supply it. */
    Optional<List<Need>> costs(ServerLevel level, SchematicPlacement.Launch launch, Set<BlockPos> met) {
        BlockState anchor = wanted(launch.anchor());
        if (anchor.getBlock() instanceof LiquidBlock) {
            return met.contains(launch.anchor()) ? Optional.of(List.of()) : Costs.poured(anchor);
        }
        List<BlockPos> charged = launch.charged().stream().filter(cell -> !met.contains(cell)).toList();
        return SchematicPlacement.costs(level, new SchematicPlacement.Launch(launch.anchor(), launch.produces(), charged),
            this::wanted, this::dataAt);
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
