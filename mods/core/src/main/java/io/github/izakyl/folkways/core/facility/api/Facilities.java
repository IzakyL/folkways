package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.PartEndpoint;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import io.github.izakyl.folkways.core.world.api.Structures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Owns the installed projections of loaded facilities. All operations run on the server thread. */
public final class Facilities {

    private record Key(ServerLevel level, UUID colony, UUID facility) {
    }

    private record WorldSource(ServerLevel level, Object source) {
    }

    private static final Map<Key, Installed> LOADED = new LinkedHashMap<>();
    private static final Map<WorldSource, Spatial> WORLD = new LinkedHashMap<>();

    public record Source(Object id, WorldPos anchor) {
    }

    /** All facilities whose protection covers this cell, including native prefabs without a colony. */
    public static Set<Source> protecting(ServerLevel level, BlockPos pos) {
        WorldPos cell = WorldPos.of(level, pos);
        Set<Source> sources = new LinkedHashSet<>();
        for (Installed facility : LOADED.values()) {
            if (facility.key.level() == level && facility.spatial.protects(cell)) {
                sources.add(new Source(facility.facility.id(), facility.facility.site()));
            }
        }
        for (var entry : WORLD.entrySet()) {
            if (entry.getKey().level() == level && entry.getValue().protects(cell)) {
                sources.add(new Source(entry.getKey().source(), cell));
            }
        }
        for (Facility facility : Prefabs.covering(level, pos)) {
            if (facility.geometry(level).stream().anyMatch(part ->
                    part instanceof Contribution.Protection protection && protection.cells().contains(cell))) {
                sources.add(new Source(facility.id(), facility.site()));
            }
        }
        return Set.copyOf(sources);
    }

    public static List<Source> affected(ServerLevel level, Iterable<BlockPos> cells) {
        Set<Source> sources = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            sources.addAll(protecting(level, cell));
        }
        return sources.stream().sorted(java.util.Comparator.comparing((Source source) -> source.anchor().toString())
            .thenComparing(source -> source.id().toString())).toList();
    }

    public static boolean protectedAt(ServerLevel level, BlockPos pos) {
        return !protecting(level, pos).isEmpty();
    }

    public static Optional<Port<ItemStack>> part(ServerLevel level, PartEndpoint endpoint) {
        return LOADED.values().stream()
            .filter(installed -> installed.key.level() == level
                && installed.key.facility().equals(endpoint.facility()))
            .flatMap(installed -> installed.contributions.stream())
            .filter(Contribution.Parts.class::isInstance).map(Contribution.Parts.class::cast)
            .map(parts -> parts.controls().get(endpoint.control()))
            .filter(endpoint.ref()::equals)
            .findFirst().flatMap(ref -> Ports.at(level, ref.at(), ResourceKind.ITEM, ref.part()));
    }

    static java.util.stream.Stream<Facility> installed(ServerLevel level) {
        return LOADED.values().stream().filter(installed -> installed.key.level() == level)
            .map(installed -> installed.facility);
    }

    public static java.util.Set<WorldPos> extentCells(ServerLevel level, UUID colony) {
        var cells = new java.util.LinkedHashSet<WorldPos>();
        LOADED.values().stream().filter(installed -> installed.key.level() == level
                && installed.key.colony().equals(colony))
            .forEach(installed -> cells.addAll(installed.facility.extent(level).cells()));
        return cells;
    }

    public static Optional<UUID> colonyAt(ServerLevel level, WorldPos pos) {
        return ownerOf(level, pos).flatMap(facility -> LOADED.keySet().stream()
            .filter(key -> key.level() == level && key.facility().equals(facility.id())).map(Key::colony).findFirst());
    }

    public static Optional<Facility> ownerOf(ServerLevel level, WorldPos pos) {
        return FacilityOwnership.owner(level, pos);
    }

    public static Optional<Facility> ownerOf(ServerLevel level, UUID entity) {
        return FacilityOwnership.owner(level, entity);
    }

    public static Optional<ContainerFacility> container(ServerLevel level, WorldPos at) {
        return ownerOf(level, at).filter(ContainerFacility.class::isInstance).map(ContainerFacility.class::cast);
    }

    public static boolean attachedStore(ServerLevel level, BlockPos pos) {
        WorldPos at = WorldPos.of(level, pos);
        return container(level, at).map(container -> container.network() && !container.access().contains(at))
            .orElseGet(() -> FacilityOwnership.suspendedAccess(level, at));
    }

    public static Optional<UUID> owningStorePart(ServerLevel level, BlockPos pos) {
        for (WorldPos cell : ContainerFacility.chestCells(level, WorldPos.of(level, pos))) {
            var owner = ownerOf(level, cell).filter(facility -> !(facility instanceof ContainerFacility))
                .filter(facility -> !(facility instanceof FacilityDefinition definition)
                    || definition.ownsStoreCell(cell)).map(Facility::id);
            if (owner.isPresent()) {
                return owner;
            }
        }
        return Optional.empty();
    }

    public static Optional<UUID> ownershipConflict(ServerLevel level, UUID facility) {
        return FacilityOwnership.conflict(level, facility);
    }

    public static Optional<UUID> owningPart(ServerLevel level, BlockPos pos) {
        return ownerOf(level, WorldPos.of(level, pos)).filter(facility -> !(facility instanceof ContainerFacility))
            .map(Facility::id);
    }

    public static void ownershipChanged(ServerLevel level) {
        FacilityOwnership.invalidate(level);
    }

    public static void ownershipBlockChanged(ServerLevel level, BlockPos pos) {
        FacilityOwnership.blockChanged(level, pos);
        ExternalAttachments.blockChanged(level, pos);
    }

    private Facilities() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Pre.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                for (Installed installed : List.copyOf(LOADED.values())) {
                    if (installed.key.level() == level && LOADED.get(installed.key) == installed) {
                        if (installed.loaded()) {
                            installed.refresh();
                        } else {
                            unregister(installed.key);
                        }
                    }
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                FacilityOwnership.entityChanged(level, event.getEntity().getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                FacilityOwnership.entityChanged(level, event.getEntity().getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                FacilityOwnership.chunkChanged(level, event.getChunk().getPos(), false);
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                FacilityOwnership.chunkChanged(level, event.getChunk().getPos(), true);
                removeWhere(key -> key.level() == level
                    && LOADED.get(key).chunk.equals(event.getChunk().getPos()));
            }
        });
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                FacilityOwnership.unload(level);
                removeWhere(key -> key.level() == level);
                WORLD.entrySet().removeIf(entry -> {
                    if (entry.getKey().level() != level) {
                        return false;
                    }
                    entry.getValue().update(List.of());
                    return true;
                });
            }
        });
    }

    static void refreshPrefabs(ServerLevel level) {
        for (Installed installed : List.copyOf(LOADED.values())) {
            if (installed.key.level() == level && installed.prefab && installed.loaded()) {
                installed.refresh();
            }
        }
    }

    /** Registers a loaded facility and returns a handle to remove this registration. */
    public static Runnable register(ServerLevel level, Colony colony, Facility facility) {
        return register(level, colony, facility, Optional.empty());
    }

    static Runnable register(ServerLevel level, Colony colony, Facility facility,
                             Optional<ProcessingWorkshop> processing) {
        if (!facility.site().in(level) || !level.isLoaded(facility.site().block(level))) {
            throw new IllegalArgumentException("facility anchor is not loaded");
        }
        Key key = new Key(level, colony.id(), facility.id());
        if (LOADED.containsKey(key)) {
            throw new IllegalStateException("facility is already registered: " + facility.id());
        }
        Installed installed = new Installed(key, colony, facility, processing);
        try {
            installed.refresh();
        } catch (RuntimeException exception) {
            installed.clear();
            throw exception;
        }
        LOADED.put(key, installed);
        ownershipChanged(level);
        facility.extent(level).cells().forEach(at -> InventoryFeedback.store(level, at));
        return () -> {
            if (LOADED.get(key) == installed) {
                unregister(key);
            }
        };
    }

    public static boolean registered(ServerLevel level, UUID colony, UUID facility) {
        return LOADED.containsKey(new Key(level, colony, facility));
    }

    /**
     * Replaces the world geometry projected by a native tracker, including facilities that have no colony owner.
     * The tracker owns discovery and refresh. An empty list or unloading the level withdraws its projections.
     */
    public static void projectSpatial(ServerLevel level, Object source, List<Contribution> contributions) {
        WorldSource key = new WorldSource(level, Objects.requireNonNull(source));
        if (contributions.isEmpty()) {
            Spatial installed = WORLD.remove(key);
            if (installed != null) {
                installed.update(List.of());
            }
            return;
        }
        if (contributions.stream().anyMatch(one -> one instanceof Contribution.Hop
                || one instanceof Contribution.Workshop || one instanceof Contribution.Lane)) {
            throw new IllegalArgumentException("planning projections require a colony");
        }
        WORLD.computeIfAbsent(key, ignored -> new Spatial(level, key)).update(contributions);
    }

    public static void unregister(ServerLevel level, UUID colony, UUID facility) {
        unregister(new Key(level, colony, facility));
    }

    private static void removeWhere(Predicate<Key> matches) {
        List<Key> removed = LOADED.keySet().stream().filter(matches).toList();
        removed.forEach(Facilities::unregister);
    }

    private static void unregister(Key key) {
        Installed installed = LOADED.remove(key);
        if (installed != null) {
            ownershipChanged(key.level());
            installed.facility.extent(key.level()).cells().forEach(at -> InventoryFeedback.store(key.level(), at));
            installed.processing.ifPresent(ProcessingWorkshop::close);
            installed.clear();
        }
    }

    private static final class Installed {

        private final Optional<ProcessingWorkshop> processing;
        private Optional<Contribution.Workshop> workshop = Optional.empty();
        private List<Contribution> geometry = List.of();

        private final Key key;
        private final Colony colony;
        private final Facility facility;
        private final ChunkPos chunk;
        private final ResourceLocation workshopSource;
        private List<TransportPublication.Offering> publishedLanes = List.of();
        private List<Workshop> publishedTransit = List.of();
        private final Map<Contribution.Lane, LaneTransport> lanes = new LinkedHashMap<>();
        private final Spatial spatial;
        private final boolean prefab;
        private Map<String, Value> readings;
        private List<Contribution> contributions = List.of();
        private FacilityExtent extent = FacilityExtent.EMPTY;

        private Installed(Key key, Colony colony, Facility facility, Optional<ProcessingWorkshop> processing) {
            this.key = key;
            this.colony = colony;
            this.facility = facility;
            this.processing = processing;
            spatial = new Spatial(key.level(), key);
            chunk = new ChunkPos(facility.site().block(key.level()));
            prefab = !(facility instanceof FacilityDefinition)
                && Prefabs.nativeCovering(key.level(), facility.site().block(key.level())).stream()
                    .anyMatch(nativePrefab -> nativePrefab.id().equals(facility.id()));
            workshopSource = ResourceLocation.fromNamespaceAndPath("folkways_core", "facility/" + facility.id());
        }

        private boolean loaded() {
            ServerLevel level = key.level();
            return facility.site().in(level) && level.isLoaded(facility.site().block(level));
        }

        private void refresh() {
            ServerLevel level = key.level();
            FacilityExtent nextExtent = facility.extent(level);
            if (!extent.equals(nextExtent)) {
                extent = nextExtent;
                ownershipChanged(level);
            }
            if (prefab && Prefabs.covered(level, facility) || LOADED.containsKey(key)
                    && ownershipConflict(level, facility.id()).isPresent()) {
                if (readings != null) {
                    clear();
                    readings = null;
                }
                return;
            }
            Map<String, Value> now = facility instanceof FacilityDefinition definition ? definition.projectionReadings(level)
                : Map.copyOf(Readings.snapshot(level, facility.readings(level)));
            var nextWorkshop = processing.flatMap(process -> process.project(level));
            if (!now.equals(readings) || !nextWorkshop.equals(workshop)) {
                if (!now.equals(readings)) {
                    geometry = List.copyOf(new LinkedHashSet<>(facility.projections(level, now)));
                }
                List<Contribution> next = new ArrayList<>(geometry);
                nextWorkshop.ifPresent(next::add);
                update(next);
                readings = now;
                workshop = nextWorkshop;
            }
            publishLanes();
        }

        private void update(List<Contribution> next) {
            ServerLevel level = key.level();
            var beforeHops = contributions.stream().filter(Contribution.Hop.class::isInstance).toList();
            var afterHops = next.stream().filter(Contribution.Hop.class::isInstance)
                .map(Contribution.Hop.class::cast).toList();
            var beforeWork = contributions.stream().filter(Contribution.Workshop.class::isInstance).toList();
            var afterWork = next.stream().filter(Contribution.Workshop.class::isInstance)
                .map(Contribution.Workshop.class::cast).toList();
            spatial.update(next);
            if (!beforeHops.equals(afterHops)) {
                Passages.publish(level, key.colony(), key, afterHops.stream().map(Contribution.Hop::hop).toList());
            }
            if (!contributions.equals(next)) {
                contributions = next;
                ownershipChanged(level);
            }
            if (!beforeWork.equals(afterWork)) {
                colony.publish(workshopSource, level.dimension(),
                    afterWork.stream().map(Contribution.Workshop::workshop).toList());
            }
        }

        private void publishLanes() {
            ServerLevel level = key.level();
            lanes.entrySet().removeIf(entry -> {
                if (contributions.contains(entry.getKey())) {
                    return false;
                }
                entry.getValue().close();
                return true;
            });
            Map<String, ControlRef> controls = new LinkedHashMap<>();
            for (Contribution contribution : contributions) {
                if (contribution instanceof Contribution.Parts parts) {
                    controls.putAll(parts.controls());
                }
            }
            for (Contribution contribution : contributions) {
                if (contribution instanceof Contribution.Lane lane && !lanes.containsKey(lane)) {
                    LaneTransport.observe(level, facility.id(), lane, controls).ifPresent(mode -> lanes.put(lane, mode));
                }
            }
            var offerings = lanes.values().stream().filter(mode -> mode.available(level.getGameTime()))
                .map(LaneTransport::offering).toList();
            if (!publishedLanes.equals(offerings)) {
                colony.publishTransport(workshopSource, level.dimension(), offerings);
                publishedLanes = offerings;
            }
            List<Workshop> transit = lanes.values().stream().filter(mode -> mode.available(level.getGameTime()))
                .flatMap(mode -> mode.transit().stream()).toList();
            if (!publishedTransit.equals(transit)) {
                colony.publish(workshopSource.withSuffix("/lanes"), level.dimension(), transit);
                publishedTransit = transit;
            }
        }

        private void clear() {
            update(List.of());
            publishLanes();
        }
    }

    private static final class Spatial {

        private final ServerLevel level;
        private final Object source;
        private final Map<Contribution, Runnable> installed = new LinkedHashMap<>();

        private Spatial(ServerLevel level, Object source) {
            this.level = level;
            this.source = source;
        }

        private boolean protects(WorldPos cell) {
            return installed.keySet().stream().anyMatch(part ->
                part instanceof Contribution.Protection protection && protection.cells().contains(cell));
        }

        private void update(List<Contribution> next) {
            installed.entrySet().removeIf(entry -> {
                if (next.contains(entry.getKey())) {
                    return false;
                }
                entry.getValue().run();
                return true;
            });
            for (Contribution contribution : next) {
                switch (contribution) {
                    case Contribution.Keepout keepout -> installed.computeIfAbsent(keepout, unused ->
                        Keepouts.install(level, source, keepout.area()));
                    case Contribution.Closure closure -> installed.computeIfAbsent(closure, unused ->
                        Closures.close(level, source, closure.cell().realm(),
                            BoundingBox.fromCorners(closure.cell().cell(), closure.cell().cell()), closure.impassable()));
                    case Contribution.Obstacle obstacle -> installed.computeIfAbsent(obstacle, unused ->
                        Structures.install(level, source, obstacle.obstacles()));
                    case Contribution.Protection protection -> installed.putIfAbsent(protection, () -> { });
                    case Contribution.Hop ignored -> { }
                    case Contribution.Workshop ignored -> { }
                    case Contribution.Lane ignored -> { }
                    case Contribution.Parts ignored -> { }
                }
            }
        }
    }
}
