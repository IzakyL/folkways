package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Placement is Create's schematicannon, and nothing more: the blueprint is read as the cannon's printer reads it,
 * each shot is placed as the cannon places it, and a shot fired is a shot done. Orchestration decides the order and
 * the hands; it never changes what a shot writes. With Create installed its own code is used, otherwise a copy of it.
 */
public final class SchematicPlacement {

    /**
     * One shot of the cannon: the cell aimed at, the target cells it leaves placed (a door's upper half comes with
     * its lower one), and the cells whose requirements the shot pays for.
     */
    public record Launch(BlockPos anchor, Set<BlockPos> produces, List<BlockPos> charged) {

        public Launch {
            anchor = anchor.immutable();
            produces = Set.copyOf(produces);
            charged = List.copyOf(charged);
        }

        public static Launch of(BlockPos anchor, Set<BlockPos> produces) {
            return new Launch(anchor, produces, List.of(anchor));
        }
    }

    /** The schematic as the printer sees it, once it has been placed into an empty schematic level. */
    public interface Printed {

        BlockState state(BlockPos pos);

        @Nullable
        BlockEntity entity(BlockPos pos);
    }

    public interface Adapter {

        /** Places the template into the printer's empty level, as the printer does when it loads a schematic. */
        Printed print(ServerLevel level, StructureTemplate template, Vec3i size);

        /** The printer's {@code setZeroAge}: what every read state is turned into before anything else sees it. */
        BlockState zeroAge(BlockState state);

        void turn(BlockEntity entity, Mirror mirror, Rotation rotation);

        /** What the cannon carries of a block entity's data: only what it trusts, never contents. */
        @Nullable
        CompoundTag prepare(ServerLevel level, BlockState state, @Nullable BlockEntity entity);

        /** Cells the cannon never aims at: they are made by another shot, or not at all. */
        boolean ignored(BlockState state);

        Costs.Quote requirement(BlockState state, @Nullable BlockEntity entity);

        /** Places a schematic cell using Folkways rules and integration-specific hooks. */
        void place(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack, @Nullable CompoundTag data);

        default Optional<Launch> launch(Function<BlockPos, BlockState> target, BlockPos pos) {
            BlockState state = target.apply(pos);
            if (ignored(state)) {
                return Optional.empty();
            }
            Set<BlockPos> produces = new LinkedHashSet<>();
            produces.add(pos.immutable());
            partner(state, pos).filter(other -> {
                BlockState there = target.apply(other);
                return there.is(state.getBlock()) && ignored(there);
            }).ifPresent(produces::add);
            return Optional.of(Launch.of(pos, produces));
        }

        /** Fires a shot whose cells are already addressed in the world. */
        default void fire(ServerLevel level, Launch launch, Function<BlockPos, BlockState> states,
                Function<BlockPos, BlockData> data, ItemStack stack) {
            BlockState state = states.apply(launch.anchor());
            BlockData kept = data.apply(launch.anchor());
            if (StructureCompat.of(state).isPresent()) {
                StructurePayload payload = kept.payload(state);
                StructureCompat.inspect(state, payload);
                CompoundTag nativeData = StructureCompat.of(state).orElseThrow()
                    .placementData(level.registryAccess(), launch.anchor(), state, payload);
                place(level, launch.anchor(), state, stack, nativeData);
            } else {
                BlockEntity entity = kept.entity(level, launch.anchor(), state);
                place(level, launch.anchor(), state, stack, prepare(level, state, entity));
            }
        }
    }

    private static volatile Adapter adapter = new VanillaSchematics();

    private SchematicPlacement() {
    }

    public static void install(Adapter installed) {
        adapter = installed;
    }

    public static Adapter adapter() {
        return adapter;
    }

    /** The parts vanilla blocks make for themselves when placed: a two-high block's top, a bed's head. */
    static Optional<BlockPos> partner(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(pos.above());
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return Optional.of(pos.relative(state.getValue(BedBlock.FACING)));
        }
        return Optional.empty();
    }

    /**
     * Resolves a blueprint into what the cannon would print: states and block entities read back from the printer's
     * level and aged back to their start. A cell the printer cannot keep (gone after placement, made by no shot, or
     * with no valid requirement) is dropped, as the cannon skips it. Fluid sources stay: they are poured as a step of
     * their own, outside the cannon's rules.
     */
    static Blueprint resolve(ServerLevel level, Blueprint source) {
        Adapter rules = adapter;
        Printed printed = rules.print(level, template(level, source), source.volume().size());
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        Map<BlockPos, BlockData> data = new LinkedHashMap<>();
        for (BlueprintBlock cell : source.blocks()) {
            BlockPos pos = cell.offset();
            if (cell.state().getBlock() instanceof LiquidBlock) {
                if (cell.state().getFluidState().isSource()) {
                    states.put(pos, cell.state());
                }
                continue;
            }
            if (StructureCompat.of(cell.state()).isPresent()) {
                StructureCompat.inspect(cell.state(), cell.data().payload(cell.state()));
                states.put(pos, cell.state());
                data.put(pos, BlockData.of(cell.data().payload(cell.state()).tag()));
                continue;
            }
            BlockState read = rules.zeroAge(printed.state(pos));
            if (read.isAir() && !cell.state().isAir()) {
                continue;
            }
            states.put(pos, read);
            BlockEntity entity = printed.entity(pos);
            if (entity != null) {
                for (UnloadedBlock.Turn turn : cell.data().turns()) {
                    rules.turn(entity, turn.mirror(), turn.rotation());
                }
                CompoundTag saved = StructureCompat.of(read).map(handler -> handler.capture(entity))
                    .orElseGet(() -> entity.saveWithFullMetadata(level.registryAccess()));
                data.put(pos, BlockData.of(StructureCompat.tagged(read, saved).tag()));
            }
        }
        Set<BlockPos> kept = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, BlockState> cell : states.entrySet()) {
            BlockState state = cell.getValue();
            if (StructureCompat.of(state).isPresent()) {
                kept.add(cell.getKey());
                continue;
            }
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                kept.add(cell.getKey());
                continue;
            }
            rules.launch(pos -> states.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
                cell.getKey()).ifPresent(launch -> {
                    boolean valid = launch.charged().stream().noneMatch(charged -> rules.requirement(
                        states.get(charged), entity(level, charged, states.get(charged),
                            data.getOrDefault(charged, BlockData.EMPTY))).invalid());
                    if (valid) {
                        launch.produces().stream().filter(states::containsKey).forEach(kept::add);
                    }
                });
        }
        List<BlueprintBlock> target = new ArrayList<>();
        states.forEach((pos, state) -> {
            if (kept.contains(pos)) {
                target.add(new BlueprintBlock(pos, state, data.getOrDefault(pos, BlockData.EMPTY)));
            }
        });
        return new Blueprint(source.id(), source.name(), target, source.unloaded(), source.joints(), source.volume(),
            source.createdGameTime(), true);
    }

    /** Raises an accepted blueprint through the same bounded printer used by creative placement. */
    static java.util.concurrent.CompletableFuture<Void> print(ServerLevel level, Blueprint resolved, BlockPos anchor) {
        return CreativePrinting.enqueue(level, resolved, anchor);
    }

    /** A validated whole operation whose individual shots can be spread across server ticks. */
    static final class Printing {
        private final ServerLevel level;
        private final Adapter printer = adapter;
        private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        private final Map<BlockPos, BlockData> data = new LinkedHashMap<>();
        private final java.util.Iterator<Map.Entry<BlockPos, BlockState>> remaining;
        private final long started = System.nanoTime();
        private final long payloads;
        private long activeNanos;
        private long longestSlice;
        private int slices;
        final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();

        Printing(ServerLevel level, Blueprint resolved, BlockPos anchor) {
            this.level = level;
            // Charge cached copies too, before any world mutation. Applying the accepted work is sliced below;
            // a legitimate large carving must not be abandoned halfway through a synchronous time budget.
            try (var processing = StructureCompat.processing()) {
                for (BlueprintBlock cell : resolved.blocks()) {
                    StructureCompat.inspectPlacement(cell.state(), cell.data().payload(cell.state()));
                    states.put(cell.worldPos(anchor), cell.state());
                    data.put(cell.worldPos(anchor), cell.data());
                }
            }
            payloads = resolved.blocks().stream().filter(cell -> cell.data().present()).count();
            remaining = states.entrySet().iterator();
        }

        boolean advance(long deadline) {
            long began = System.nanoTime();
            try {
                // At least one shot makes progress even when it alone exceeds the preferred slice duration.
                while (remaining.hasNext()) {
                    var cell = remaining.next();
                    place(cell.getKey(), cell.getValue());
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                }
                return !remaining.hasNext();
            } finally {
                long elapsed = System.nanoTime() - began;
                activeNanos += elapsed;
                longestSlice = Math.max(longestSlice, elapsed);
                slices++;
            }
        }

        private void place(BlockPos pos, BlockState state) {
            Function<BlockPos, BlockState> target = cell -> states.getOrDefault(cell, Blocks.AIR.defaultBlockState());
            Function<BlockPos, BlockData> payload = cell -> data.getOrDefault(cell, BlockData.EMPTY);
            if (state.isAir()) {
                level.setBlock(pos, state, Rubble.SHOT);
            } else if (state.getBlock() instanceof LiquidBlock) {
                level.setBlock(pos, state, Block.UPDATE_ALL);
            } else {
                printer.launch(target, pos).ifPresent(launch -> {
                    // Creative placement spends nothing; quoting a microstructure's display icon would only
                    // repeat its expensive material calculation and serialize the carving again.
                    ItemStack display = StructureCompat.of(state).isPresent() ? ItemStack.EMPTY
                        : icon(costs(level, launch, target, payload).orElse(List.of()));
                    printer.fire(level, launch, target, payload, display);
                    BlockState laid = level.getBlockState(pos);
                    if (Step.soaked(state) && laid.is(state.getBlock())) {
                        level.setBlock(pos, laid.setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
                    }
                    for (BlockPos cell : launch.produces()) {
                        BlockEntity entity = level.getBlockEntity(cell);
                        if (entity != null) {
                            entity.setChanged();
                            level.sendBlockUpdated(cell, entity.getBlockState(), entity.getBlockState(),
                                Block.UPDATE_CLIENTS);
                        }
                    }
                });
            }
        }

        void complete() {
            org.slf4j.LoggerFactory.getLogger("folkways-patterns").info(
                "CREATIVE_PRINT cells={} payloads={} millis={} activeMillis={} maxSliceMillis={} slices={}",
                states.size(), payloads, (System.nanoTime() - started) / 1_000_000D,
                activeNanos / 1_000_000D, longestSlice / 1_000_000D, slices);
            done.complete(null);
        }
    }

    /** The block entity the cannon reckons a cell's requirement from: a fresh one, given only the trusted data. */
    @Nullable
    static BlockEntity entity(ServerLevel level, BlockPos pos, BlockState state, BlockData data) {
        BlockEntity stored = data.entity(level, pos, state);
        if (stored == null) {
            return null;
        }
        CompoundTag prepared = adapter.prepare(level, state, stored);
        if (StructureCompat.of(state).isPresent()) {
            if (prepared == null) throw StructureCompat.invalid();
            return BlockData.of(prepared).entity(level, pos, state);
        }
        BlockEntity fresh = BlockData.EMPTY.entity(level, pos, state);
        if (fresh != null && prepared != null) fresh.loadWithComponents(prepared, level.registryAccess());
        return fresh;
    }

    /** What a shot costs, or nothing when the colony cannot represent it. */
    static Optional<List<Need>> costs(ServerLevel level, Launch launch, Function<BlockPos, BlockState> states,
            Function<BlockPos, BlockData> data) {
        Map<ItemSpec, Long> counted = new LinkedHashMap<>();
        for (BlockPos cell : launch.charged()) {
            BlockState state = states.apply(cell);
            var handler = StructureCompat.of(state);
            if (handler.isPresent()) {
                var volume = handler.get().voxels(data.apply(cell).payload(state));
                volume.counts().forEach((material, count) -> counted.merge(material,
                    (long) (count + volume.perBlock() - 1) / volume.perBlock(), Long::sum));
                continue;
            }
            Costs.Quote quote = adapter.requirement(state, entity(level, cell, state, data.apply(cell)));
            if (quote.needs().isEmpty()) {
                return Optional.empty();
            }
            quote.needs().get().forEach(need -> counted.merge(need.spec(), need.count(), Long::sum));
        }
        return Optional.of(counted.entrySet().stream().map(entry -> new Need(entry.getKey(), entry.getValue())).toList());
    }

    /** The stack a shot is shown carrying, handed to the block as it is placed: its first requirement. */
    static ItemStack icon(List<Need> cost) {
        return cost.stream().findFirst().map(need -> Goods.display(need.spec())).orElse(ItemStack.EMPTY);
    }

    // The blueprint as a structure template, so the printer's level receives exactly what a schematic would give it.
    private static StructureTemplate template(ServerLevel level, Blueprint source) {
        CompoundTag tag = new CompoundTag();
        Vec3i size = source.volume().size();
        tag.put("size", ints(size.getX(), size.getY(), size.getZ()));
        ListTag palette = new ListTag();
        Map<BlockState, Integer> indices = new LinkedHashMap<>();
        ListTag blocks = new ListTag();
        for (BlueprintBlock cell : source.blocks()) {
            if (cell.state().getBlock() instanceof LiquidBlock) {
                continue;
            }
            int index = indices.computeIfAbsent(cell.state(), state -> {
                palette.add(NbtUtils.writeBlockState(state));
                return palette.size() - 1;
            });
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(cell.offset().getX(), cell.offset().getY(), cell.offset().getZ()));
            block.putInt("state", index);
            if (cell.data().present() && StructureCompat.of(cell.state()).isEmpty()) {
                block.put("nbt", cell.data().tag());
            }
            blocks.add(block);
        }
        tag.put("palette", palette);
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), tag);
        return template;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }
}
