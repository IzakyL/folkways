package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

// Cached grades survive a restart for inspection; only a fresh loaded-world survey can authorize dispatch.
public final class AirportRegistry extends SavedData {
    private static final Map<ServerLevel, AirportRegistry> LIVE = new java.util.IdentityHashMap<>();
    private ServerLevel level;
    private final List<CompoundTag> framedTowers = new ArrayList<>();
    private final List<CompoundTag> framedAirports = new ArrayList<>();
    private final NavigableMap<BlockPos, String> names = new TreeMap<>();
    private final Map<BlockPos, WorldPos> registeredAddresses = new LinkedHashMap<>();
    private final Map<BlockPos, Airport> cached = new LinkedHashMap<>();
    private final Map<String, Set<BlockPos>> byName = new LinkedHashMap<>();
    private final Set<BlockPos> invalidated = new LinkedHashSet<>();
    private final Map<BlockPos, AirportScan> active = new LinkedHashMap<>();
    private final Map<BlockPos, AirportScan> dependencies = new LinkedHashMap<>();
    private final Map<BlockPos, CompoundTag> addresses = new LinkedHashMap<>();
    private final Map<BlockPos, WorldSpaces.Frame> frames = new LinkedHashMap<>();
    private long changeVisits;
    private final Map<BlockPos, Set<BlockPos>> overlaps = new LinkedHashMap<>();

    long changeVisits() {
        return changeVisits;
    }

    public static AirportRegistry of(ServerLevel level) {
        AirportRegistry registry = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(AirportRegistry::new,
            (tag, registries) -> load(tag)), "folkways_airports");
        registry.level = level;
        LIVE.put(level, registry);
        registry.restoreFrames(level);
        return registry;
    }

    void restoreFrames(ServerLevel level) {
        this.level = level;
        framedTowers.removeIf(entry -> {
            var address = WorldPos.load(Reader.of(entry.getCompound("address")));
            var storage = address.flatMap(at -> WorldSpaces.storage(level, at));
            if (storage.isEmpty()) {
                return false;
            }
            putName(storage.get(), entry.getString("name"));
            addresses.put(storage.get(), entry.getCompound("address").copy());
            invalidated.add(storage.get());
            return true;
        });
        framedAirports.removeIf(entry -> {
            var frame = WorldSpaces.frame(level, entry.getUUID("frame"));
            if (frame.isEmpty()) {
                return false;
            }
            Airport airport = AirportData.read(AirportData.offset(entry.getCompound("geometry"),
                frame.get().storageOrigin()));
            List<BlockPos> members = airport.towers().isEmpty() ? List.of(airport.tower())
                : airport.towers().stream().map(TowerCoverage.Tower::pos).toList();
            members.stream().filter(names::containsKey).forEach(member -> {
                cached.put(member, airport);
                frames.put(member, frame.get());
            });
            return true;
        });
    }

    public List<BlockPos> towers() {
        return List.copyOf(names.keySet());
    }

    /** Registration includes frame addresses waiting for their structure to load. */
    public boolean registered(String id) {
        return names.keySet().stream().anyMatch(tower -> {
            var current = WorldSpaces.at(level, tower);
            if (WorldSpaces.containing(level, tower).isPresent() || !registeredAddresses.containsKey(tower)) {
                registeredAddresses.put(tower, current);
            }
            return registeredAddresses.get(tower).toString().equals(id);
        })
            || framedTowers.stream().map(entry -> WorldPos.load(Reader.of(entry.getCompound("address"))))
                .flatMap(Optional::stream).anyMatch(address -> address.toString().equals(id));
    }

    // A key cursor stays valid when towers are added or removed between survey ticks.
    BlockPos nextTower(BlockPos after) {
        if (after != null) {
            return names.higherKey(after);
        }
        return names.isEmpty() ? null : names.firstKey();
    }

    boolean contains(BlockPos pos) {
        return names.containsKey(pos);
    }

    void register(BlockPos pos) {
        if (!names.containsKey(pos)) {
            putName(pos.immutable(), "");
            setDirty();
        }
    }

    void remove(BlockPos pos) {
        dependencies.remove(pos);
        active.remove(pos);
        String old = names.remove(pos);
        registeredAddresses.remove(pos);
        if (old != null) {
            forgetName(pos, old);
            cached.remove(pos);
            invalidated.remove(pos);
            addresses.remove(pos);
            frames.remove(pos);
            clearOverlaps(pos);
            setDirty();
        }
    }

    void rename(BlockPos pos, String name) {
        rename(null, pos, name);
    }

    void rename(ServerLevel level, BlockPos pos, String name) {
        if (!names.containsKey(pos)) {
            return;
        }
        String next = name.strip().replaceAll("[\\p{Cntrl}]", "");
        if (next.length() > 64) {
            next = next.substring(0, 64);
        }
        if (!next.equals(names.get(pos))) {
            Airport airport = cached.get(pos);
            List<BlockPos> members = airport == null || airport.towers().isEmpty() ? List.of(pos)
                : airport.towers().stream().map(TowerCoverage.Tower::pos).toList();
            var colony = level == null ? Optional.empty() : TowerDuty.colony(level, pos).map(value -> value.id());
            for (BlockPos member : members) {
                if (names.containsKey(member) && (member.equals(pos) || level != null
                        && Airports.validTower(level, member)
                        && colony.isPresent()
                        && colony.equals(TowerDuty.colony(level, member).map(value -> value.id())))) {
                    putName(member, next);
                }
            }
            setDirty();
        }
    }

    private void putName(BlockPos pos, String name) {
        String old = names.put(pos, name);
        if (level != null) {
            registeredAddresses.put(pos, WorldSpaces.at(level, pos));
        }
        if (old != null) {
            forgetName(pos, old);
        }
        byName.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(pos);
    }

    private void forgetName(BlockPos pos, String name) {
        Set<BlockPos> indexed = byName.get(name);
        indexed.remove(pos);
        if (indexed.isEmpty()) {
            byName.remove(name);
        }
    }

    List<BlockPos> named(String name) {
        return List.copyOf(byName.getOrDefault(name, Set.of()));
    }

    List<Airport> neighbors(BlockPos tower) {
        return cached.values().stream().filter(airport -> !airport.tower().equals(tower)
            && Math.abs((long) airport.tower().getX() - tower.getX()) <= Airport.RADIUS * 2
            && Math.abs((long) airport.tower().getZ() - tower.getZ()) <= Airport.RADIUS * 2).toList();
    }

    static Set<BlockPos> overlapping(Airport airport, List<Airport> neighbors) {
        List<Footprint> areas = airport.footprints();
        Set<BlockPos> found = new LinkedHashSet<>();
        for (Airport other : neighbors) {
            List<Footprint> otherAreas = other.footprints();
            if (areas.stream().anyMatch(area -> otherAreas.stream().anyMatch(area::overlaps))) {
                found.add(other.tower());
            }
        }
        return Set.copyOf(found);
    }

    void cache(Airport airport) {
        cache(airport, overlapping(airport, neighbors(airport.tower())));
    }

    void cache(Airport airport, Set<BlockPos> shared) {
        List<BlockPos> members = airport.towers().isEmpty() ? List.of(airport.tower())
            : airport.towers().stream().map(TowerCoverage.Tower::pos).toList();
        boolean complete = airport.flaws().stream().noneMatch(problem -> problem.kind() == AirportFlaw.UNLOADED);
        for (BlockPos member : members) {
            if (names.containsKey(member)) {
                Airport old = cached.get(member);
                if (complete || old == null || old.flaws().stream()
                        .anyMatch(problem -> problem.kind() == AirportFlaw.UNLOADED)) {
                    cached.put(member, airport);
                    setDirty();
                    if (complete) {
                        invalidated.remove(member);
                    }
                }
            }
        }
    }

    Optional<TowerCoverage.Tower> description(BlockPos pos) {
        Airport airport = cached.get(pos);
        return airport == null ? Optional.empty()
            : airport.towers().stream().filter(tower -> tower.pos().equals(pos)).findFirst();
    }

    void watch(AirportScan scan) {
        active.put(scan.tower(), scan);
    }

    void complete(AirportScan scan) {
        active.remove(scan.tower(), scan);
        if (scan.result().flaws().stream().noneMatch(problem -> problem.kind() == AirportFlaw.UNLOADED)) {
            dependencies.put(scan.tower(), scan);
        }
        scan.releaseCopy();
    }

    public static void changed(ServerLevel level, BlockPos pos,
                               net.minecraft.world.level.block.state.BlockState oldState,
                               net.minecraft.world.level.block.state.BlockState newState) {
        if (!level.getServer().isSameThread()) {
            return;
        }
        AirportRegistry registry = LIVE.get(level);
        if (registry == null) {
            return;
        }
        registry.changeVisits++;
        registry.changed(registry.dependencies, pos, oldState, newState);
        registry.changed(registry.active, pos, oldState, newState);
    }

    private void changed(Map<BlockPos, AirportScan> surveys, BlockPos pos,
                         net.minecraft.world.level.block.state.BlockState oldState,
                         net.minecraft.world.level.block.state.BlockState newState) {
        for (AirportScan scan : surveys.values()) {
            if (scan.changed(pos, oldState, newState)) {
                invalidated.add(scan.tower());
                Airports.request(level, scan.tower());
            }
        }
    }

    void changed(BlockPos tower) {
        invalidated.add(tower);
        AirportScan scan = active.get(tower);
        if (scan != null) {
            scan.invalidate();
        }
    }

    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        LIVE.keySet().removeIf(level -> level.getServer() == event.getServer());
    }

    private boolean stale(BlockPos tower) {
        return invalidated.contains(tower);
    }

    boolean pending(BlockPos tower) {
        Airport airport = cached.get(tower);
        return stale(tower) || airport != null && airport.towers().stream()
            .anyMatch(member -> stale(member.pos()));
    }

    private void clearOverlaps(BlockPos tower) {
        Set<BlockPos> old = overlaps.remove(tower);
        if (old != null) {
            for (BlockPos other : old) {
                Set<BlockPos> shared = overlaps.get(other);
                if (shared != null) {
                    shared.remove(tower);
                }
            }
        }
    }

    Airport airport(ServerLevel level, BlockPos pos) {
        String name = names.getOrDefault(pos, "");
        Airport found = cached.get(pos);
        if (found == null) {
            found = new Airport(name, pos, List.of(), List.of(), List.of(), GroundGraph.empty(),
                List.of(new Airport.Problem(AirportFlaw.UNLOADED, pos, "")));
        }
        List<Airport.Problem> flaws = new ArrayList<>(found.flaws());
        if (name.isBlank()) {
            flaws.add(new Airport.Problem(AirportFlaw.UNNAMED, pos, ""));
        } else if (byName.getOrDefault(name, Set.of()).stream().map(other -> {
            Airport geometry = cached.get(other);
            return geometry == null ? other : geometry.tower();
        }).distinct().count() > 1) {
            flaws.add(new Airport.Problem(AirportFlaw.SHARED_NAME, pos, name));
        }
        if (found.towers().stream().anyMatch(tower -> !names.getOrDefault(tower.pos(), "").equals(name))) {
            flaws.add(new Airport.Problem(AirportFlaw.NAME_MISMATCH, pos, ""));
        }
        if (pending(pos)
                && flaws.stream().noneMatch(problem -> problem.kind() == AirportFlaw.UNLOADED)) {
            flaws.add(new Airport.Problem(AirportFlaw.UNLOADED, pos, ""));
        }
        for (Airport other : cached.values().stream().distinct().toList()) {
            if (!other.tower().equals(found.tower()) && found.footprints().stream()
                    .anyMatch(area -> other.footprints().stream().anyMatch(area::overlaps))) {
                flaws.add(new Airport.Problem(AirportFlaw.OVERLAP, other.tower(), ""));
            }
        }
        List<TowerCoverage.Tower> towers = found.towers().stream().map(tower ->
            new TowerCoverage.Tower(tower.pos(), tower.facing(), tower.colony(), TowerDuty.staffed(level, tower.pos())))
            .toList();
        return new Airport(name, found.tower(), found.runways(), found.helipads(), found.stands(), found.ground(),
            flaws, towers, found.coverage());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag towers = new ListTag();
        names.forEach((pos, name) -> {
            CompoundTag entry = new CompoundTag();
            var frame = level == null ? Optional.<WorldSpaces.Frame>empty() : WorldSpaces.containing(level, pos);
            if (frame.isPresent()) {
                var address = WorldSpaces.at(level, pos).save();
                addresses.put(pos, address.copy());
                frames.put(pos, frame.get());
                entry.put("address", address);
            } else if (addresses.containsKey(pos)) {
                entry.put("address", addresses.get(pos).copy());
            } else {
                entry.putLong("pos", pos.asLong());
            }
            entry.putString("name", name);
            towers.add(entry);
        });
        framedTowers.forEach(entry -> towers.add(entry.copy()));
        tag.put("towers", towers);
        tag.putInt("SurveyVersion", 1);
        ListTag airports = new ListTag();
        cached.values().stream().distinct().forEach(airport -> {
            var frame = level == null ? Optional.<WorldSpaces.Frame>empty()
                : WorldSpaces.containing(level, airport.tower());
            if (frame.isPresent()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("frame", frame.get().id());
                entry.put("geometry", AirportData.offset(AirportData.write(airport),
                    BlockPos.ZERO.subtract(frame.get().storageOrigin())));
                airports.add(entry);
            } else if (frames.containsKey(airport.tower())) {
                var savedFrame = frames.get(airport.tower());
                CompoundTag entry = new CompoundTag();
                entry.putUUID("frame", savedFrame.id());
                entry.put("geometry", AirportData.offset(AirportData.write(airport),
                    BlockPos.ZERO.subtract(savedFrame.storageOrigin())));
                airports.add(entry);
            } else {
                airports.add(AirportData.write(airport));
            }
        });
        framedAirports.forEach(entry -> airports.add(entry.copy()));
        tag.put("airports", airports);
        return tag;
    }

    static AirportRegistry load(CompoundTag tag) {
        AirportRegistry registry = new AirportRegistry();
        for (Tag value : tag.getList("towers", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (entry.contains("address")) {
                registry.framedTowers.add(entry.copy());
            } else {
                registry.putName(BlockPos.of(entry.getLong("pos")), entry.getString("name"));
            }
        }
        if (tag.getInt("SurveyVersion") == 1) {
            for (Tag value : tag.getList("airports", Tag.TAG_COMPOUND)) {
                try {
                    CompoundTag entry = (CompoundTag) value;
                    if (entry.hasUUID("frame")) {
                        registry.framedAirports.add(entry.copy());
                        continue;
                    }
                    Airport airport = AirportData.read(entry);
                    if (airport.ground().graded()) {
                        List<BlockPos> members = airport.towers().isEmpty() ? List.of(airport.tower())
                            : airport.towers().stream().map(TowerCoverage.Tower::pos).toList();
                        members.stream().filter(registry.names::containsKey)
                            .forEach(member -> registry.cached.put(member, airport));
                    }
                } catch (RuntimeException ignored) {
                    // Invalid cached geometry requires a new survey; airport names remain registered.
                }
            }
        }
        registry.invalidated.addAll(registry.names.keySet());
        return registry;
    }
}
