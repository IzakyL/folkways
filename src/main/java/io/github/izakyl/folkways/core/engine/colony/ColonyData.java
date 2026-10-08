package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.ColonyLifecycle;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

public final class ColonyData {

    private final UUID colonyId;

    private final Holdings holdings;

    private final Keeps keeps = new Keeps();

    private final ColonyWorks works = new ColonyWorks(this);

    private Runnable dirtyCallback = () -> {
    };

    private MinecraftServer server;

    public ColonyData(UUID colonyId) {
        this.colonyId = colonyId;
        this.holdings = new Holdings(colonyId);
        holdings.setReleasedListener(works::released);
        wire(holdings, keeps);
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
        if (!holdings.references(moved.keySet()) && !keeps.references(moved.keySet())) {
            return;
        }
        holdings.relocate(moved);
        Runnable relocateExtensions = ColonyLifecycle.prepareRelocation(server, works, moved);
        works.refresh(server, () -> {
            keeps.relocate(moved);
            relocateExtensions.run();
        });
        ColonyWays.forget(colonyId);
        dirtyCallback.run();
    }

    public void reconcile(MinecraftServer server) {
        holdings.reconcile(server);
    }

    public Holdings holdings() {
        return holdings;
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
            .blob("holdings", holdings.save())
            .blob("keeps", keeps.save())
            .tag();
    }

    static Optional<ColonyData> load(Reader reader) {
        Optional<UUID> saved = reader.uuid("colonyId");
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        ColonyData data = new ColonyData(saved.get());
        reader.child("holdings").ifPresent(data.holdings::load);
        reader.child("keeps").ifPresent(data.keeps::load);
        return Optional.of(data);
    }

    private void wire(ColonyChangeSource... sources) {
        for (ColonyChangeSource source : sources) {
            source.setDirtyListener(() -> dirtyCallback.run());
        }
    }
}
