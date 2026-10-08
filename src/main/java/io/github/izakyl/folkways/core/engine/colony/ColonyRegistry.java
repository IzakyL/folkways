package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ColonyRegistry extends SavedData {
    private static final String DATA_NAME = "folkways_colonies";
    private static final SavedData.Factory<ColonyRegistry> FACTORY = new SavedData.Factory<>(ColonyRegistry::new, ColonyRegistry::load);
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");

    private final boolean writable;

    private final Map<UUID, ColonyData> colonies = new LinkedHashMap<>();
    private final Set<UUID> razed = new LinkedHashSet<>();

    private ColonyRegistry() {
        this(true);
    }

    private ColonyRegistry(boolean writable) {
        this.writable = writable;
    }

    private static ColonyRegistry sealed() {
        return new ColonyRegistry(false);
    }

    @Override
    public void setDirty(boolean dirty) {
        if (writable || !dirty) {
            super.setDirty(dirty);
        }
    }

    public static ColonyRegistry get(MinecraftServer server) {
        ColonyRegistry registry = server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        for (ColonyData colony : List.copyOf(registry.colonies.values())) {
            colony.on(server);
            ColonyRuntime.settle(server, colony.works());
        }
        return registry;
    }

    public Optional<ColonyData> find(UUID colonyId) {
        return Optional.ofNullable(colonies.get(colonyId));
    }

    public ColonyData mint(MinecraftServer server) {
        ColonyData created = new ColonyData(UUID.randomUUID());
        created.setDirtyCallback(this::setDirty);
        created.on(server);
        colonies.put(created.colonyId(), created);
        setDirty();
        ColonyRuntime.settle(server, created.works());
        return created;
    }

    public boolean isRazed(UUID colonyId) {
        return razed.contains(colonyId);
    }

    public void raze(MinecraftServer server, UUID colonyId) {
        ColonyData colony = colonies.remove(colonyId);
        if (colony != null) {
            colony.holdings().forgetAll(server);
            colony.works().closed(Closing.RAZED);
            ColonyWays.forget(colonyId);
        }
        razed.add(colonyId);
        setDirty();
    }

    // Whether any colony already holds this block or entity: each is held by one colony at most.
    boolean taken(Held what) {
        return colonies.values().stream().anyMatch(colony -> colony.holdings().taken(what));
    }

    public Collection<ColonyData> colonies() {
        return Collections.unmodifiableCollection(colonies.values());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        SaveVersion.write(tag);
        return Writer.of(tag)
            .children("colonies", colonies.values(), colony -> colony.save(new CompoundTag()))
            .uuids("razed", razed)
            .tag();
    }

    static ColonyRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
        Reader reader = Reader.of(tag);
        if (!SaveVersion.isReadable(reader)) {
            LOGGER.error("The colony save carries schema version {}, which this build does not read; this"
                + " world's colonies are left unread, and nothing will be written over them.",
                SaveVersion.of(reader).orElseThrow());
            return sealed();
        }
        ColonyRegistry registry = new ColonyRegistry();
        registry.razed.addAll(reader.uuids("razed"));
        for (ColonyData colony : Entries.of(reader, "colonies", ColonyData::load)) {
            registry.adopt(colony);
        }
        return registry;
    }

    private void adopt(ColonyData colony) {
        colony.setDirtyCallback(this::setDirty);
        colonies.put(colony.colonyId(), colony);
    }
}
