package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// Sparse repairs use the builder's shots and inputs, with a stricter guard against replacing player changes.
public final class MaintenanceOrders {
    private MaintenanceOrders() {
    }

    /** Freezes a baseline through the same printer used when a blueprint is confirmed. */
    public static Map<BlockPos, BlockState> resolve(ServerLevel level, Map<BlockPos, BlockState> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        BlockPos corner = source.keySet().stream().reduce(BlockPos::min).orElseThrow();
        List<BlueprintBlock> cells = source.entrySet().stream()
            .filter(entry -> SchematicPlacement.partner(entry.getValue(), entry.getKey())
                .map(source::containsKey).orElse(true))
            .map(entry -> new BlueprintBlock(entry.getKey().subtract(corner), entry.getValue(), BlockData.EMPTY)).toList();
        if (cells.isEmpty()) {
            return Map.of();
        }
        BlueprintVolume volume = BlueprintVolume.around(cells.stream().map(BlueprintBlock::offset).toList())
            .orElseThrow(() -> new IllegalArgumentException("baseline exceeds the schematic printer's bounds"));
        Blueprint printed = new Blueprint(UUID.randomUUID(), "maintenance", cells, volume, level.getGameTime()).confirm(level);
        Map<BlockPos, BlockState> resolved = new LinkedHashMap<>();
        printed.blocks().forEach(cell -> resolved.put(cell.offset().offset(corner), cell.state()));
        return Collections.unmodifiableMap(resolved);
    }

    public static boolean buildingAt(Colony colony, ServerLevel level, WorldPos at) {
        return colony.service(BuildContent.ID, BuildPresence.class)
            .map(presence -> presence.buildingAt(level, at)).orElse(false);
    }

    public static List<Node> missing(ServerLevel level, Map<WorldPos, BlockState> baseline,
            ResourceLocation owner, Vocation vocation, ResourceLocation doing, BooleanSupplier enabled,
            Predicate<WorldPos> available, RefusalKind conflict, BiConsumer<Node, Ending> ended) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        Map<BlockPos, WorldPos> addresses = new LinkedHashMap<>();
        baseline.forEach((at, state) -> {
            if (at.in(level)) {
                BlockPos pos = at.block(level);
                cells.put(pos, state);
                addresses.put(pos, at);
            }
        });
        Function<BlockPos, BlockState> target = pos -> cells.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        List<Node> nodes = new ArrayList<>();
        Set<BlockPos> handled = new HashSet<>();
        for (BlockPos at : cells.keySet()) {
            if (handled.contains(at) || SchematicPlacement.partner(cells.get(at), at)
                    .filter(part -> !cells.containsKey(part)).isPresent()) {
                continue;
            }
            Optional<SchematicPlacement.Launch> shot = SchematicPlacement.adapter().launch(target, at);
            if (shot.isEmpty() || !cells.keySet().containsAll(shot.get().produces())) {
                continue;
            }
            SchematicPlacement.Launch launch = shot.get();
            handled.addAll(launch.produces());
            // A partial multi-block has no independent cannon shot; report it rather than resetting its other half.
            if (launch.produces().stream().anyMatch(pos -> !level.isLoaded(pos)
                    || !level.getBlockState(pos).isAir())) {
                continue;
            }
            Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, at).stream()
                .filter(pos -> !launch.produces().contains(pos)).toList());
            Optional<List<Need>> quote = SchematicPlacement.costs(level, launch, target, pos -> BlockData.EMPTY);
            if (stances.isEmpty() || quote.isEmpty()) {
                continue;
            }
            Map<WorldPos, BlockState> before = new LinkedHashMap<>();
            Map<WorldPos, BlockState> after = new LinkedHashMap<>();
            launch.produces().forEach(pos -> {
                before.put(addresses.get(pos), level.getBlockState(pos));
                after.put(addresses.get(pos), target.apply(pos));
            });
            BuildJob job = new BuildJob(BuildJob.Kind.LAY, addresses.get(at), stances.get(), before, after,
                List.of(), quote.get(), Map.of(), after.keySet());
            LayNode lay = new LayNode(job, (node, how) -> { });
            NodeSpec original = lay.spec();
            NodeSpec spec = new NodeSpec(original.id(), owner, original.site(), original.stances(), original.needs(),
                original.gives(), original.leaves(), original.tools(), original.workload(), original.effort(),
                original.pace(), Optional.of(vocation), original.gesture(), original.focus(), original.estimate(),
                doing, original.about(), original.subject());
            nodes.add(new Node() {
                @Override
                public NodeSpec spec() { return spec; }

                @Override
                public boolean ready(ServerLevel world) {
                    return enabled.getAsBoolean() && lay.ready(world) && after.keySet().stream().allMatch(available);
                }

                @Override
                public Outcome commit(ServerLevel world, Worker who) {
                    if (!ready(world)) {
                        return Outcome.failed(BuildRefusal.CELL_NOT_LOADED);
                    }
                    if (!Reach.standingIn(who.body(), spec.stances())) {
                        return Outcome.failed(BuildRefusal.NO_WAY_TO_BUILD);
                    }
                    if (after.entrySet().stream().allMatch(cell ->
                            world.getBlockState(cell.getKey().block(world)) == cell.getValue())) {
                        return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
                    }
                    if (after.keySet().stream().anyMatch(cell -> !world.getBlockState(cell.block(world)).isAir())) {
                        return Outcome.failed(conflict);
                    }
                    return lay.commit(world, who);
                }

                @Override
                public void ended(ServerLevel world, Ending how) { ended.accept(this, how); }

                @Override
                public Recourse failing(ServerLevel world, RefusalKind why) { return Recourse.waitFor(100); }
            });
        }
        return List.copyOf(nodes);
    }
}
