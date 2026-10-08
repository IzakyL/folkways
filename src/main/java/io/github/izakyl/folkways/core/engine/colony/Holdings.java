package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
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
public final class Holdings implements ColonyChangeSource {

    private static final String TAG_ID = "id";
    private static final String TAG_OWNER = "owner";
    private static final String TAG_ENTITY = "entity";
    private static final String TAG_TYPE = "type";
    private static final String TAG_BLOCK = "block";
    private static final String TAG_AREA = "area";
    private static final String TAG_LINE = "line";
    private static final String TAG_REALM = "realm";
    private static final String TAG_CELLS = "cells";

    private final UUID colony;
    private final Map<UUID, Holding> held = new LinkedHashMap<>();
    private final Map<WorldPos, UUID> blocks = new HashMap<>();
    private final Map<UUID, UUID> entities = new HashMap<>();
    // The held bodies loaded into the world right now: never saved, filled again as they load.
    private final Set<UUID> present = new LinkedHashSet<>();
    private Runnable dirtyListener = () -> {
    };
    private BiConsumer<Holding, Release> releasedListener = (holding, why) -> {
    };

    Holdings(UUID colony) {
        this.colony = Objects.requireNonNull(colony, "colony");
    }

    @Override
    public void setDirtyListener(Runnable dirtyListener) {
        this.dirtyListener = dirtyListener;
    }

    void setReleasedListener(BiConsumer<Holding, Release> releasedListener) {
        this.releasedListener = releasedListener;
    }

    Optional<Holding> hold(MinecraftServer server, ResourceLocation owner, Held what) {
        if (taken(what)) {
            return Optional.empty();
        }
        Holding holding = new Holding(UUID.randomUUID(), owner, what);
        put(holding);
        switch (what) {
            case Held.Block block -> blockEntity(server, block.cell())
                .ifPresent(entity -> ColonyMembership.join(entity, colony));
            case Held.Entity entity -> bodyOf(server, entity.entity()).ifPresent(body -> {
                body.joinColony(colony);
                if (!body.mob().isRemoved()) {
                    present.add(body.id());
                }
            });
            case Held.Area ignored -> { }
            case Held.Line ignored -> { }
        }
        dirtyListener.run();
        return Optional.of(holding);
    }

    Optional<Holding> release(MinecraftServer server, UUID id, Release why) {
        Holding gone = held.get(id);
        if (gone == null) {
            return Optional.empty();
        }
        drop(gone);
        switch (gone.what()) {
            case Held.Block block -> blockEntity(server, block.cell())
                .filter(this::marked).ifPresent(ColonyMembership::leave);
            case Held.Entity entity -> {
                present.remove(entity.entity());
                if (why != Release.DIED && server != null) {
                    bodyOf(server, entity.entity()).ifPresent(Body::leaveColony);
                }
            }
            case Held.Area ignored -> { }
            case Held.Line ignored -> { }
        }
        dirtyListener.run();
        releasedListener.accept(gone, why);
        return Optional.of(gone);
    }

    boolean taken(Held what) {
        return switch (what) {
            case Held.Block block -> blocks.containsKey(block.cell());
            case Held.Entity entity -> entities.containsKey(entity.entity());
            case Held.Area ignored -> false;
            case Held.Line ignored -> false;
        };
    }

    public List<Holding> all() {
        return List.copyOf(held.values());
    }

    public List<Holding> of(ResourceLocation owner) {
        List<Holding> found = new ArrayList<>();
        for (Holding holding : held.values()) {
            if (holding.owner().equals(owner)) {
                found.add(holding);
            }
        }
        return List.copyOf(found);
    }

    public Optional<Holding> get(UUID id) {
        return Optional.ofNullable(held.get(id));
    }

    public Optional<Holding> at(WorldPos cell) {
        return Optional.ofNullable(blocks.get(cell)).map(held::get);
    }

    public Optional<Holding> ofEntity(UUID entity) {
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
        for (Holding holding : held.values()) {
            if (holding.what() instanceof Held.Entity(UUID entity, ResourceLocation type)
                    && ResidentKinds.of(type).isPresent()) {
                found.add(new Resident(entity, type));
            }
        }
        return List.copyOf(found);
    }

    // Every cell anything held lies on.
    Set<WorldPos> cells() {
        Set<WorldPos> found = new LinkedHashSet<>();
        held.values().forEach(holding -> found.addAll(holding.what().cells()));
        return found;
    }

    // A body bound to this colony came into the world: one the colony let go while it was away is told so now.
    void entered(Body body) {
        if (!entities.containsKey(body.id())) {
            body.leaveColony();
            return;
        }
        present.add(body.id());
    }

    void exited(UUID entity) {
        present.remove(entity);
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

    // Lets go every held block whose block entity, loaded, no longer carries this colony.
    void reconcile(MinecraftServer server) {
        for (Holding holding : List.copyOf(held.values())) {
            if (!(holding.what() instanceof Held.Block block)) {
                continue;
            }
            WorldSpaces.Block where = WorldSpaces.resolve(server, block.cell()).orElse(null);
            if (where == null || !where.level().isLoaded(where.cell())) {
                continue;
            }
            BlockEntity entity = where.level().getBlockEntity(where.cell());
            if (entity == null || !marked(entity)) {
                release(server, holding.id(), Release.GONE);
            }
        }
    }

    boolean references(Set<WorldPos> moved) {
        return held.values().stream().anyMatch(holding -> holding.what().cells().stream().anyMatch(moved::contains));
    }

    void relocate(Map<WorldPos, WorldPos> moved) {
        boolean changed = false;
        for (Holding holding : List.copyOf(held.values())) {
            Held now = holding.what().moved(moved);
            if (!now.equals(holding.what())) {
                drop(holding);
                put(holding.moved(now));
                changed = true;
            }
        }
        if (changed) {
            dirtyListener.run();
        }
    }

    // The colony is gone: unmark what can be reached now; the rest is unmarked as it loads, against the razed list.
    void forgetAll(MinecraftServer server) {
        for (Holding holding : held.values()) {
            if (holding.what() instanceof Held.Block block) {
                blockEntity(server, block.cell()).filter(this::marked).ifPresent(ColonyMembership::leave);
            }
        }
        if (!held.isEmpty()) {
            held.clear();
            blocks.clear();
            entities.clear();
            present.clear();
            dirtyListener.run();
        }
    }

    private void put(Holding holding) {
        held.put(holding.id(), holding);
        switch (holding.what()) {
            case Held.Block block -> blocks.put(block.cell(), holding.id());
            case Held.Entity entity -> entities.put(entity.entity(), holding.id());
            case Held.Area ignored -> { }
            case Held.Line ignored -> { }
        }
    }

    private void drop(Holding holding) {
        held.remove(holding.id());
        switch (holding.what()) {
            case Held.Block block -> blocks.remove(block.cell(), holding.id());
            case Held.Entity entity -> entities.remove(entity.entity(), holding.id());
            case Held.Area ignored -> { }
            case Held.Line ignored -> { }
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
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(entity);
            if (found != null) {
                return Bodies.of(found);
            }
        }
        return Optional.empty();
    }

    CompoundTag save() {
        return Writer.of().children("held", held.values(), Holdings::write).tag();
    }

    void load(Reader reader) {
        for (Holding holding : Entries.of(reader, "held", Holdings::read)) {
            put(holding);
        }
    }

    private static CompoundTag write(Holding holding) {
        Writer writer = Writer.of().uuid(TAG_ID, holding.id()).id(TAG_OWNER, holding.owner());
        switch (holding.what()) {
            case Held.Entity entity -> writer.uuid(TAG_ENTITY, entity.entity()).id(TAG_TYPE, entity.type());
            case Held.Block block -> writer.blob(TAG_BLOCK, block.cell().save());
            case Held.Area area -> writer.children(TAG_AREA, byRealm(area.covered()).entrySet(),
                group -> Writer.of().blob(TAG_REALM, group.getKey().save()).intArray(TAG_CELLS, packed(group.getValue())).tag());
            case Held.Line line -> writer.children(TAG_LINE, line.points(), WorldPos::save);
        }
        return writer.tag();
    }

    private static Optional<Holding> read(Reader reader) {
        Optional<UUID> id = reader.uuid(TAG_ID);
        Optional<ResourceLocation> owner = reader.id(TAG_OWNER);
        if (id.isEmpty() || owner.isEmpty()) {
            return Optional.empty();
        }
        return shape(reader).map(what -> new Holding(id.get(), owner.get(), what));
    }

    private static Optional<Held> shape(Reader reader) {
        Optional<UUID> entity = reader.uuid(TAG_ENTITY);
        Optional<ResourceLocation> type = reader.id(TAG_TYPE);
        if (entity.isPresent() && type.isPresent()) {
            return Optional.of(new Held.Entity(entity.get(), type.get()));
        }
        Optional<WorldPos> block = reader.child(TAG_BLOCK).flatMap(WorldPos::load);
        if (block.isPresent()) {
            return Optional.of(new Held.Block(block.get()));
        }
        Set<WorldPos> covered = new LinkedHashSet<>();
        for (Reader group : reader.children(TAG_AREA)) {
            Optional<Realm> realm = group.child(TAG_REALM).flatMap(Realm::load);
            int[] cells = group.intArray(TAG_CELLS).orElse(new int[0]);
            if (realm.isEmpty() || cells.length % 3 != 0) {
                continue;
            }
            for (int at = 0; at < cells.length; at += 3) {
                covered.add(new WorldPos(realm.get(), new BlockPos(cells[at], cells[at + 1], cells[at + 2])));
            }
        }
        if (!covered.isEmpty()) {
            return Optional.of(new Held.Area(covered));
        }
        List<WorldPos> points = Entries.of(reader, TAG_LINE, WorldPos::load);
        return points.size() < 2 ? Optional.empty() : Optional.of(new Held.Line(points));
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
