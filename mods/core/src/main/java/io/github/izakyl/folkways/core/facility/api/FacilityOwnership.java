package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Exclusive block and entity ownership, rebuilt on the server thread. */
final class FacilityOwnership {

    private static final Map<ServerLevel, FacilityOwnership> LEVELS = new WeakHashMap<>();

    private final Map<WorldPos, Facility> cells = new LinkedHashMap<>();
    private final Map<UUID, Facility> entities = new LinkedHashMap<>();
    private final Set<WorldPos> discovered = new LinkedHashSet<>();
    private final Map<WorldPos, List<Facility>> nativeByCell = new LinkedHashMap<>();
    private final Map<UUID, UUID> conflicts = new LinkedHashMap<>();
    private record Body(Facility facility, FacilityExtent extent) {
    }

    private final Set<BlockPos> watchedBlocks = new LinkedHashSet<>();
    private final Set<ChunkPos> watchedChunks = new LinkedHashSet<>();
    private final Set<UUID> watchedEntities = new LinkedHashSet<>();
    private final Set<WorldPos> suspendedAccess = new LinkedHashSet<>();
    private boolean rebuilding;
    private boolean refreshDiscovery;
    private boolean dirty = true;

    static void invalidate(ServerLevel level) {
        var index = LEVELS.get(level);
        if (index != null) {
            index.dirty = true;
        }
    }

    static void blockChanged(ServerLevel level, BlockPos pos) {
        var index = LEVELS.get(level);
        if (index == null) {
            return;
        }
        index.changedAt(level, pos);
        for (Direction side : Direction.values()) {
            index.changedAt(level, pos.relative(side));
        }
    }

    private void changedAt(ServerLevel level, BlockPos pos) {
        WorldPos cell = WorldPos.of(level, pos);
        discovered.remove(cell);
        if (nativeByCell.remove(cell) != null || watchedBlocks.contains(pos)) {
            refreshDiscovery = true;
            dirty = true;
        }
    }

    static void chunkChanged(ServerLevel level, ChunkPos chunk, boolean unloaded) {
        var index = LEVELS.get(level);
        if (index == null) {
            return;
        }
        if (unloaded) {
            index.discovered.removeIf(cell -> !cell.in(level) || new ChunkPos(cell.block(level)).equals(chunk));
            index.nativeByCell.keySet().retainAll(index.discovered);
        }
        if (index.watchedChunks.contains(chunk)) {
            index.refreshDiscovery = true;
            index.dirty = true;
        }
    }

    static void entityChanged(ServerLevel level, UUID entity) {
        var index = LEVELS.get(level);
        if (index != null && index.watchedEntities.contains(entity)) {
            index.dirty = true;
        }
    }

    static void unload(ServerLevel level) {
        LEVELS.remove(level);
    }

    static Optional<Facility> owner(ServerLevel level, WorldPos cell) {
        var index = LEVELS.computeIfAbsent(level, ignored -> new FacilityOwnership());
        if (!index.discovered.contains(cell) && cell.in(level) && level.isLoaded(cell.block(level))) {
            index.discover(level, cell);
        }
        return Optional.ofNullable(current(level).cells.get(cell));
    }

    static Optional<Facility> owner(ServerLevel level, UUID entity) {
        return Optional.ofNullable(current(level).entities.get(entity));
    }

    static boolean suspendedAccess(ServerLevel level, WorldPos pos) {
        return current(level).suspendedAccess.contains(pos);
    }

    static Optional<UUID> conflict(ServerLevel level, UUID facility) {
        return Optional.ofNullable(current(level).conflicts.get(facility));
    }

    private void discover(ServerLevel level, WorldPos cell) {
        var found = Prefabs.nativeCovering(level, cell.block(level));
        discovered.add(cell);
        if (!found.isEmpty()) {
            nativeByCell.put(cell, found);
            dirty = true;
        }
    }

    private static FacilityOwnership current(ServerLevel level) {
        var index = LEVELS.computeIfAbsent(level, ignored -> new FacilityOwnership());
        if (index.rebuilding) {
            return index;
        }
        index.rebuilding = true;
        try {
            var attachments = ExternalAttachments.facilities(level);
            if (index.dirty) {
                index.dirty = false;
                try {
                    index.rebuild(level, attachments);
                } catch (RuntimeException failure) {
                    index.dirty = true;
                    throw failure;
                }
            }
        } finally {
            index.rebuilding = false;
        }
        return index;
    }

    private void rebuild(ServerLevel level, List<ContainerFacility> attachments) {
        if (refreshDiscovery) {
            refreshDiscovery = false;
            nativeByCell.replaceAll((cell, found) -> cell.in(level)
                ? Prefabs.nativeCovering(level, cell.block(level)) : List.of());
            nativeByCell.values().removeIf(List::isEmpty);
        }
        // Definitions are validated against one another. They override native bodies and suspend covered stores.
        var candidates = new LinkedHashMap<UUID, Facility>();
        DefinedFacilities.forOwnership(level).forEach(facility -> candidates.putIfAbsent(facility.id(), facility));
        Facilities.installed(level).filter(FacilityDefinition.class::isInstance)
            .forEach(facility -> candidates.putIfAbsent(facility.id(), facility));
        // Attachment networks absorb their members before individual store claims are considered.
        attachments.forEach(facility -> candidates.putIfAbsent(facility.id(), facility));
        var nativeFacilities = new LinkedHashMap<UUID, Facility>();
        Facilities.installed(level).forEach(facility -> nativeFacilities.putIfAbsent(facility.id(), facility));
        nativeByCell.values().forEach(found ->
            found.forEach(facility -> nativeFacilities.putIfAbsent(facility.id(), facility)));
        var colonies = Colonies.all(level.getServer());
        colonies.stream().flatMap(colony -> HeldFacilities.all(colony).stream())
            .filter(held -> held.site().in(level))
            .forEach(held -> nativeFacilities.putIfAbsent(held.id(), held));
        var stores = colonies.stream().flatMap(colony -> colony.storesIn(level.dimension()).stream())
            .distinct().filter(at -> at.in(level)).sorted(Comparator.comparing(WorldPos::toString)).toList();
        watchedBlocks.clear();
        watchedChunks.clear();
        watchedEntities.clear();
        stores.forEach(at -> watch(level, at));
        var loadedStores = stores.stream().filter(at -> level.isLoaded(at.block(level))).toList();
        for (WorldPos at : loadedStores) {
            Prefabs.nativeCovering(level, at.block(level)).forEach(facility ->
                nativeFacilities.putIfAbsent(facility.id(), facility));
        }
        // Held and native bodies precede enrolled stores. Within this tier, the lowest UUID wins.
        nativeFacilities.values().stream().filter(facility -> !(facility instanceof FacilityDefinition))
            .sorted(Comparator.comparing(Facility::id))
            .forEach(facility -> candidates.putIfAbsent(facility.id(), facility));

        List<Body> next = new ArrayList<>();
        candidates.values().forEach(facility -> {
            var extent = facility.extent(level);
            if (!(facility instanceof HeldFacilities.Held) || !extent.elements().isEmpty()) {
                next.add(new Body(facility, extent));
            }
        });
        loadedStores.forEach(at -> {
            var container = ContainerFacility.enrolled(level, at);
            next.add(new Body(container, container.extent(level)));
        });
        cells.clear();
        entities.clear();
        conflicts.clear();
        suspendedAccess.clear();
        next.forEach(candidate -> {
            watch(level, candidate.facility().site());
            add(level, candidate);
        });
    }

    private void watch(ServerLevel level, WorldPos cell) {
        WorldSpaces.storage(level, cell).ifPresent(pos -> {
            watchedBlocks.add(pos);
            watchedChunks.add(new ChunkPos(pos));
        });
    }

    private void add(ServerLevel level, Body candidate) {
        Facility facility = candidate.facility();
        FacilityExtent extent = candidate.extent();
        var body = extent.cells();
        body.forEach(cell -> watch(level, cell));
        var ids = extent.elements().stream().filter(FacilityExtent.Entity.class::isInstance)
            .map(FacilityExtent.Entity.class::cast).map(FacilityExtent.Entity::id).toList();
        watchedEntities.addAll(ids);
        if (facility instanceof ContainerFacility container && !container.network()
                && suspendedAccess.contains(facility.site())) {
            return;
        }
        Facility existing = body.stream().map(cells::get).filter(Objects::nonNull).findFirst()
            .orElseGet(() -> ids.stream().map(entities::get).filter(Objects::nonNull).findFirst().orElse(null));
        if (existing != null && !existing.id().equals(facility.id())) {
            conflicts.put(facility.id(), existing.id());
            if (facility instanceof ContainerFacility container && container.network()) {
                suspendedAccess.addAll(container.access());
            }
            return;
        }
        body.forEach(cell -> cells.putIfAbsent(cell, facility));
        ids.forEach(id -> entities.putIfAbsent(id, facility));
    }
}
