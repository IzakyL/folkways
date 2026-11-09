package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

// Everything a colony holds, by shape and by whom it is held for. A held block carries the colony on its block
// entity and a held body on its own state, so the world can be checked against the list: the list is what the
// colony holds, the marks are how a block or a body knows it.
public final class Claims implements ColonyChangeSource {

    private static final String TAG_ID = "id";
    private static final String TAG_OWNER = "owner";
    private static final String TAG_ENTITY = "entity";
    private static final String TAG_TYPE = "type";
    private static final String TAG_BLOCK = "block";
    private static final String TAG_POINT = "point";
    private static final String TAG_AREA = "area";
    private static final String TAG_LINE = "line";
    private static final String TAG_REALM = "realm";
    private static final String TAG_CELLS = "cells";

    private final UUID colony;
    private final Map<UUID, Claim> held = new LinkedHashMap<>();
    private final Map<WorldPos, UUID> blocks = new HashMap<>();
    private final Map<UUID, UUID> entities = new HashMap<>();
    // The held bodies loaded into the world right now: never saved, filled again as they load.
    private final Set<UUID> present = new LinkedHashSet<>();
    // The held bodies something carrying them took out of the world, to put back as they were: a train's riders while
    // their carriage runs over ground no one has loaded. Still here and at their work, only not simulated meanwhile.
    private final Set<UUID> carriedOff = new LinkedHashSet<>();
    private Runnable dirtyListener = () -> {
    };
    private BiConsumer<Claim, ClaimRelease> releasedListener = (claim, why) -> {
    };

    Claims(UUID colony) {
        this.colony = Objects.requireNonNull(colony, "colony");
    }

    @Override
    public void setDirtyListener(Runnable dirtyListener) {
        this.dirtyListener = dirtyListener;
    }

    void setReleasedListener(BiConsumer<Claim, ClaimRelease> releasedListener) {
        this.releasedListener = releasedListener;
    }

    Optional<Claim> hold(MinecraftServer server, ResourceLocation owner, Claimed what) {
        if (taken(what)) {
            return Optional.empty();
        }
        Optional<Body> joining = what instanceof Claimed.Entity entity ? bodyOf(server, entity.entity()) : Optional.empty();
        if (what instanceof Claimed.Entity entity && ResidentKinds.of(entity.type())
                .filter(kind -> kind.workDefaults()).isPresent() && joining.isEmpty()) {
            return Optional.empty();
        }
        Claim claim = new Claim(UUID.randomUUID(), owner, what);
        put(claim);
        switch (what) {
            case Claimed.Block block -> blockEntity(server, block.cell())
                .ifPresent(entity -> ColonyMembership.join(entity, colony));
            case Claimed.Entity entity -> joining.ifPresent(body -> {
                WorkDefaultApplication.apply(ColonyData.find(server, colony)
                    .map(data -> data.keeps().kept(WorkDefaults.ID)).orElseGet(CompoundTag::new), body);
                body.joinColony(colony);
                if (!body.mob().isRemoved()) {
                    present.add(body.id());
                }
            });
            case Claimed.Point ignored -> { }
            case Claimed.Area ignored -> { }
            case Claimed.Line ignored -> { }
        }
        dirtyListener.run();
        return Optional.of(claim);
    }

    Optional<Claim> release(MinecraftServer server, UUID id, ClaimRelease why) {
        Claim gone = held.get(id);
        if (gone == null) {
            return Optional.empty();
        }
        drop(gone);
        switch (gone.what()) {
            case Claimed.Block block -> blockEntity(server, block.cell())
                .filter(this::marked).ifPresent(ColonyMembership::leave);
            case Claimed.Entity entity -> {
                present.remove(entity.entity());
                carriedOff.remove(entity.entity());
                if (why != ClaimRelease.DIED && server != null) {
                    bodyOf(server, entity.entity()).ifPresent(Body::leaveColony);
                }
            }
            case Claimed.Point ignored -> { }
            case Claimed.Area ignored -> { }
            case Claimed.Line ignored -> { }
        }
        dirtyListener.run();
        releasedListener.accept(gone, why);
        return Optional.of(gone);
    }

    boolean taken(Claimed what) {
        return switch (what) {
            case Claimed.Block block -> blocks.containsKey(block.cell());
            case Claimed.Point point -> blocks.containsKey(point.cell());
            case Claimed.Entity entity -> entities.containsKey(entity.entity());
            case Claimed.Area ignored -> false;
            case Claimed.Line ignored -> false;
        };
    }

    public List<Claim> all() {
        return List.copyOf(held.values());
    }

    public List<Claim> of(ResourceLocation owner) {
        List<Claim> found = new ArrayList<>();
        for (Claim claim : held.values()) {
            if (claim.owner().equals(owner)) {
                found.add(claim);
            }
        }
        return List.copyOf(found);
    }

    public Optional<Claim> get(UUID id) {
        return Optional.ofNullable(held.get(id));
    }

    public Optional<Claim> at(WorldPos cell) {
        return Optional.ofNullable(blocks.get(cell)).map(held::get);
    }

    public Optional<Claim> ofEntity(UUID entity) {
        return Optional.ofNullable(entities.get(entity)).map(held::get);
    }

    // Every held block.
    public Set<WorldPos> blocks() {
        return Set.copyOf(blocks.keySet());
    }

    // Every held entity, loaded or not.
    public Set<UUID> entities() {
        return Set.copyOf(entities.keySet());
    }

    // The held entities of a kind of resident, loaded or not.
    public List<Resident> residents() {
        List<Resident> found = new ArrayList<>();
        for (Claim claim : held.values()) {
            if (claim.what() instanceof Claimed.Entity(UUID entity, ResourceLocation type)
                    && ResidentKinds.of(type).isPresent()) {
                found.add(new Resident(entity, type));
            }
        }
        return List.copyOf(found);
    }

    // Every cell anything held lies on.
    Set<WorldPos> cells() {
        Set<WorldPos> found = new LinkedHashSet<>();
        held.values().forEach(claim -> found.addAll(claim.what().cells()));
        return found;
    }

    // A body bound to this colony came into the world: one the colony let go while it was away is told so now.
    void entered(Body body) {
        if (!entities.containsKey(body.id())) {
            body.leaveColony();
            return;
        }
        present.add(body.id());
        carriedOff.remove(body.id());
    }

    void exited(UUID entity) {
        present.remove(entity);
    }

    void carriedOff(UUID entity) {
        if (entities.containsKey(entity)) {
            carriedOff.add(entity);
        }
    }

    public boolean isCarriedOff(UUID entity) {
        return carriedOff.contains(entity);
    }

    public Set<UUID> carriedOff() {
        return Set.copyOf(carriedOff);
    }

    // The held bodies loaded and ticking in this level.
    public List<Body> bodiesIn(ServerLevel level) {
        List<Body> found = new ArrayList<>();
        for (UUID id : List.copyOf(present)) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved() && level.isPositionEntityTicking(entity.blockPosition())) {
                Bodies.of(entity).ifPresent(found::add);
            }
        }
        return List.copyOf(found);
    }

    // Loaded blocks keep their entity mark; native points keep satisfying their declared predicate.
    void reconcile(MinecraftServer server) {
        for (Claim claim : List.copyOf(held.values())) {
            reconcile(server, claim);
        }
    }

    void reconcile(MinecraftServer server, WorldPos cell) {
        at(cell).ifPresent(claim -> reconcile(server, claim));
    }

    private void reconcile(MinecraftServer server, Claim claim) {
        WorldPos cell = claim.what().singleCell().orElse(null);
        if (cell == null) return;
        WorldSpaces.Block where = WorldSpaces.resolve(server, cell).orElse(null);
        if (where == null || !where.level().isLoaded(where.cell())) {
            return;
        }
        BlockEntity entity = where.level().getBlockEntity(where.cell());
        boolean valid = claim.what() instanceof Claimed.Point
            ? PointSites.accepts(claim.owner(), where.level(), where.cell()) : entity != null && marked(entity);
        if (!valid) {
            release(server, claim.id(), ClaimRelease.GONE);
        }
    }

    boolean references(Set<WorldPos> moved) {
        return held.values().stream().anyMatch(claim -> claim.what().cells().stream().anyMatch(moved::contains));
    }

    void relocate(Map<WorldPos, WorldPos> moved) {
        boolean changed = false;
        for (Claim claim : List.copyOf(held.values())) {
            Claimed now = claim.what().moved(moved);
            if (!now.equals(claim.what())) {
                drop(claim);
                put(claim.moved(now));
                changed = true;
            }
        }
        if (changed) {
            dirtyListener.run();
        }
    }

    // The colony is gone: unmark what can be reached now; the rest is unmarked as it loads, against the razed list.
    void forgetAll(MinecraftServer server) {
        for (Claim claim : held.values()) {
            if (claim.what() instanceof Claimed.Block block) {
                blockEntity(server, block.cell()).filter(this::marked).ifPresent(ColonyMembership::leave);
            }
        }
        if (!held.isEmpty()) {
            held.clear();
            blocks.clear();
            entities.clear();
            present.clear();
            carriedOff.clear();
            dirtyListener.run();
        }
    }

    private void put(Claim claim) {
        held.put(claim.id(), claim);
        switch (claim.what()) {
            case Claimed.Block block -> blocks.put(block.cell(), claim.id());
            case Claimed.Point point -> blocks.put(point.cell(), claim.id());
            case Claimed.Entity entity -> entities.put(entity.entity(), claim.id());
            case Claimed.Area ignored -> { }
            case Claimed.Line ignored -> { }
        }
    }

    private void drop(Claim claim) {
        held.remove(claim.id());
        switch (claim.what()) {
            case Claimed.Block block -> blocks.remove(block.cell(), claim.id());
            case Claimed.Point point -> blocks.remove(point.cell(), claim.id());
            case Claimed.Entity entity -> entities.remove(entity.entity(), claim.id());
            case Claimed.Area ignored -> { }
            case Claimed.Line ignored -> { }
        }
    }

    private boolean marked(BlockEntity entity) {
        return ColonyMembership.owner(entity).filter(colony::equals).isPresent();
    }

    private static Optional<BlockEntity> blockEntity(MinecraftServer server, WorldPos cell) {
        if (server == null) {
            return Optional.empty();
        }
        return WorldSpaces.resolve(server, cell)
            .filter(block -> block.level().isLoaded(block.cell()))
            .map(block -> block.level().getBlockEntity(block.cell()));
    }

    private static Optional<Body> bodyOf(MinecraftServer server, UUID entity) {
        if (server == null) {
            return Optional.empty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(entity);
            if (found != null) {
                return Bodies.of(found);
            }
        }
        return Optional.empty();
    }

    CompoundTag save() {
        return Writer.of().children("held", held.values(), Claims::write).tag();
    }

    void load(Reader reader) {
        for (Reader entry : reader.requiredChildren("held")) {
            Claim claim = read(entry);
            if (held.containsKey(claim.id()) || taken(claim.what())) {
                throw new IllegalArgumentException("Duplicate persisted claim " + claim.id());
            }
            put(claim);
        }
    }

    private static CompoundTag write(Claim claim) {
        Writer writer = Writer.of().uuid(TAG_ID, claim.id()).id(TAG_OWNER, claim.owner());
        switch (claim.what()) {
            case Claimed.Entity entity -> writer.uuid(TAG_ENTITY, entity.entity()).id(TAG_TYPE, entity.type());
            case Claimed.Block block -> writer.blob(TAG_BLOCK, block.cell().save());
            case Claimed.Point point -> writer.blob(TAG_POINT, point.cell().save());
            case Claimed.Area area -> writer.children(TAG_AREA, byRealm(area.covered()).entrySet(),
                group -> Writer.of().blob(TAG_REALM, group.getKey().save()).intArray(TAG_CELLS, packed(group.getValue())).tag());
            case Claimed.Line line -> writer.children(TAG_LINE, line.points(), WorldPos::save);
        }
        return writer.tag();
    }

    private static Claim read(Reader reader) {
        return new Claim(reader.uuid(TAG_ID).orElseThrow(), reader.id(TAG_OWNER).orElseThrow(), shape(reader));
    }

    private static Claimed shape(Reader reader) {
        List<String> shapes = List.of(TAG_ENTITY, TAG_BLOCK, TAG_POINT, TAG_AREA, TAG_LINE).stream()
            .filter(reader.keys()::contains).toList();
        if (shapes.size() != 1) {
            throw new IllegalArgumentException("A persisted claim must have exactly one shape");
        }
        return switch (shapes.getFirst()) {
            case TAG_ENTITY -> new Claimed.Entity(reader.uuid(TAG_ENTITY).orElseThrow(),
                reader.id(TAG_TYPE).orElseThrow());
            case TAG_BLOCK -> new Claimed.Block(reader.child(TAG_BLOCK).flatMap(WorldPos::load).orElseThrow());
            case TAG_POINT -> new Claimed.Point(reader.child(TAG_POINT).flatMap(WorldPos::load).orElseThrow());
            case TAG_AREA -> {
                Set<WorldPos> covered = new LinkedHashSet<>();
                Set<Realm> realms = new LinkedHashSet<>();
                for (Reader group : reader.requiredChildren(TAG_AREA)) {
                    Realm realm = group.child(TAG_REALM).flatMap(Realm::load).orElseThrow();
                    int[] cells = group.intArray(TAG_CELLS).orElseThrow();
                    if (!realms.add(realm) || cells.length == 0 || cells.length % 3 != 0) {
                        throw new IllegalArgumentException("Invalid persisted claim area group");
                    }
                    for (int at = 0; at < cells.length; at += 3) {
                        if (!covered.add(new WorldPos(realm, new BlockPos(cells[at], cells[at + 1], cells[at + 2])))) {
                            throw new IllegalArgumentException("Duplicate persisted claim area cell");
                        }
                    }
                }
                yield new Claimed.Area(covered);
            }
            case TAG_LINE -> new Claimed.Line(reader.requiredChildren(TAG_LINE).stream()
                .map(point -> WorldPos.load(point).orElseThrow()).toList());
            default -> throw new IllegalArgumentException("Unknown persisted claim shape");
        };
    }

    private static Map<Realm, List<BlockPos>> byRealm(Set<WorldPos> cells) {
        Map<Realm, List<BlockPos>> found = new LinkedHashMap<>();
        cells.forEach(cell -> found.computeIfAbsent(cell.realm(), realm -> new ArrayList<>()).add(cell.cell()));
        return found;
    }

    private static int[] packed(List<BlockPos> cells) {
        int[] packed = new int[cells.size() * 3];
        int at = 0;
        for (BlockPos cell : cells) {
            packed[at++] = cell.getX();
            packed[at++] = cell.getY();
            packed[at++] = cell.getZ();
        }
        return packed;
    }
}
