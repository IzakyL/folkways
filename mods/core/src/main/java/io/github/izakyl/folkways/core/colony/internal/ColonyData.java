package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.ColonyLifecycle;
import io.github.izakyl.folkways.core.inventory.internal.InventoryIndex;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.TravelSources;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.Hops;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ColonyData implements TravelSources {

    private final UUID colonyId;

    private final Claims claims;

    private final InventoryIndex inventory;

    private final Keeps keeps = new Keeps();

    private final ColonyWorks works = new ColonyWorks(this);

    private Runnable dirtyCallback = () -> {
    };

    private MinecraftServer server;

    public ColonyData(UUID colonyId) {
        this.colonyId = colonyId;
        this.claims = new Claims(colonyId);
        this.inventory = new InventoryIndex(colonyId);
        claims.setReleasedListener(works::released);
        wire(claims, keeps);
    }

    public ColonyData() {
        this(UUID.randomUUID());
    }

    public static Optional<ColonyData> find(MinecraftServer server, UUID colonyId) {
        return ColonyRegistry.get(server).find(colonyId);
    }

    void setDirtyCallback(Runnable dirtyCallback) {
        this.dirtyCallback = dirtyCallback;
    }

    void on(MinecraftServer server) {
        this.server = server;
    }

    MinecraftServer server() {
        return server;
    }

    public UUID colonyId() {
        return colonyId;
    }

    public void relocate(MinecraftServer server,
            Map<WorldPos, WorldPos> moved) {
        if (!claims.references(moved.keySet()) && !keeps.references(moved.keySet())) {
            return;
        }
        claims.relocate(moved);
        Runnable relocateExtensions = ColonyLifecycle.prepareRelocation(server, works, moved);
        works.refresh(server, () -> {
            keeps.relocate(moved);
            relocateExtensions.run();
        });
        ColonyWays.forget(colonyId);
        dirtyCallback.run();
    }

    public void reconcile(MinecraftServer server) {
        claims.reconcile(server);
    }

    @Override
    public Iterable<Body> bodiesIn(ServerLevel level) {
        return claims.bodiesIn(level);
    }

    @Override
    public List<Hop> hops() {
        return Hops.in(works);
    }

    public Claims claims() {
        return claims;
    }

    public InventoryIndex inventory() {
        return inventory;
    }

    public Keeps keeps() {
        return keeps;
    }

    public ColonyWorks works() {
        return works;
    }

    public CompoundTag save(CompoundTag tag) {
        return Writer.of(tag)
            .uuid("colonyId", colonyId)
            .blob("claims", claims.save())
            .blob("keeps", keeps.save())
            .tag();
    }

    static Optional<ColonyData> load(Reader reader) {
        Optional<UUID> saved = reader.uuid("colonyId");
        if (saved.isEmpty() || reader.child("claims").isEmpty() || reader.child("keeps").isEmpty()) {
            return Optional.empty();
        }
        ColonyData data = new ColonyData(saved.get());
        data.claims.load(reader.child("claims").orElseThrow());
        data.keeps.load(reader.child("keeps").orElseThrow());
        return Optional.of(data);
    }

    private void wire(ColonyChangeSource... sources) {
        for (ColonyChangeSource source : sources) {
            source.setDirtyListener(() -> dirtyCallback.run());
        }
    }
}
