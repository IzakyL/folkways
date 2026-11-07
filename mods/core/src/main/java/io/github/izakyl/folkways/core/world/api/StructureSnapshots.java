package io.github.izakyl.folkways.core.world.api;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Required;
import io.github.izakyl.folkways.base.persist.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Captures block palettes and restores their saved states. */
public final class StructureSnapshots {
    private StructureSnapshots() {
    }

    public record Snapshot(WorldPos origin, Map<BlockPos, BlockState> cells, int unsupported) {
        public Snapshot {
            cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        }

        public CompoundTag save() {
            Map<BlockState, Integer> palette = new LinkedHashMap<>();
            ListTag states = new ListTag();
            int[] positions = new int[cells.size() * 4];
            int index = 0;
            for (var cell : cells.entrySet()) {
                Integer slot = palette.get(cell.getValue());
                if (slot == null) {
                    slot = palette.size();
                    palette.put(cell.getValue(), slot);
                    states.add(NbtUtils.writeBlockState(cell.getValue()));
                }
                positions[index++] = cell.getKey().getX();
                positions[index++] = cell.getKey().getY();
                positions[index++] = cell.getKey().getZ();
                positions[index++] = slot;
            }
            CompoundTag tag = Writer.of()
                .integer("schemaVersion", 1)
                // Kept for a future migration step that runs Minecraft's fixers over the palette after a game upgrade.
                .integer("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion())
                .blob("origin", origin.save())
                .intArray("cells", positions)
                .integer("unsupported", unsupported)
                .tag();
            tag.put("palette", states);
            return tag;
        }

        public Map<BlockPos, BlockState> in(ServerLevel level) {
            Map<BlockPos, BlockState> addressed = new LinkedHashMap<>();
            if (origin.in(level)) {
                BlockPos anchor = origin.block(level);
                cells.forEach((offset, state) -> addressed.put(anchor.offset(offset), state));
            }
            return addressed;
        }
    }

    public static Snapshot capture(
        ServerLevel level, WorldPos origin, Iterable<BlockPos> cells, Predicate<BlockState> accepts) {
        Map<BlockPos, BlockState> kept = new LinkedHashMap<>();
        BlockPos anchor = origin.block(level);
        for (BlockPos at : cells) {
            if (!level.isLoaded(at)) {
                throw new IllegalArgumentException("snapshot includes an unloaded cell");
            }
            BlockState state = level.getBlockState(at);
            if (accepts.test(state)) {
                kept.put(at.subtract(anchor), state);
            }
        }

        return new Snapshot(origin, kept, 0);
    }

    public static Optional<Snapshot> load(CompoundTag tag) {
        Reader reader = Reader.of(tag);
        if (reader.integer("schemaVersion").orElse(0) != 1) {
            return Optional.empty();
        }
        Optional<WorldPos> origin = reader.child("origin").flatMap(WorldPos::load);
        if (origin.isEmpty() || reader.integer("unsupported").isEmpty()
                || !tag.contains("palette", Tag.TAG_LIST) || reader.intArray("cells").isEmpty()) {
            return Optional.empty();
        }
        ListTag palette = Required.list(tag, "palette", Tag.TAG_COMPOUND);
        List<Optional<BlockState>> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag saved = palette.getCompound(i);
            if (!saved.contains("Name", Tag.TAG_STRING)) {
                return Optional.empty();
            }
            ResourceLocation id = ResourceLocation.tryParse(saved.getString("Name"));
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                BlockState base = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                for (var property : base.getProperties()) {
                    if (!saved.contains("Properties", Tag.TAG_COMPOUND)) {
                        return Optional.empty();
                    }
                    CompoundTag properties = saved.getCompound("Properties");
                    if (!properties.contains(property.getName(), Tag.TAG_STRING)
                            || property.getValue(properties.getString(property.getName())).isEmpty()) {
                        return Optional.empty();
                    }
                }
            }
            states.add(
                id == null || !BuiltInRegistries.BLOCK.containsKey(id)
                ? Optional.empty()
                : Optional.of(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), saved)));
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        int skipped = reader.integer("unsupported").orElseThrow();
        int[] positions = Required.intArray(tag, "cells");
        for (int i = 0; i + 3 < positions.length; i += 4) {
            int slot = positions[i + 3];
            if (slot < 0 || slot >= states.size() || states.get(slot).isEmpty()) {
                skipped++;
                continue;
            }
            cells.put(new BlockPos(positions[i], positions[i + 1], positions[i + 2]), states.get(slot).get());
        }
        return Optional.of(new Snapshot(origin.get(), cells, skipped));
    }
}
