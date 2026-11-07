package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Required;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.base.persist.upgrade.DataVersion;
import io.github.izakyl.folkways.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import java.nio.file.Files;
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
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ColonyRegistry extends SavedData {
    private static final String DATA_NAME = "folkways_colonies";
    private static final SavedData.Factory<ColonyRegistry> FACTORY =
        new SavedData.Factory<>(ColonyRegistry::new, ColonyRegistry::load);
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");

    private final Optional<Component> refusal;

    private final Map<UUID, ColonyData> colonies = new LinkedHashMap<>();
    private final Set<UUID> razed = new LinkedHashSet<>();

    private ColonyRegistry() {
        this(Optional.empty());
        setDirty();
    }

    private ColonyRegistry(Optional<Component> refusal) {
        this.refusal = refusal;
    }

    private static ColonyRegistry sealed(String cause) {
        return new ColonyRegistry(Optional.of(Component.literal(
            "Folkways colony save is unreadable or unsupported. " + cause
                + " This world cannot be played with this build. Use a new world. Colony data was not changed.")));
    }

    @Override
    public void setDirty(boolean dirty) {
        if (refusal.isEmpty() || !dirty) {
            super.setDirty(dirty);
        }
    }

    public void rejectPlayer(ServerPlayer player) {
        refusal.ifPresent(player.connection::disconnect);
    }

    private void requireReadable() {
        if (refusal.isPresent()) {
            throw new IllegalStateException(refusal.get().getString());
        }
    }

    public static ColonyRegistry get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        ColonyRegistry registry = storage.get(FACTORY, DATA_NAME);
        if (registry == null) {
            var file = server.getWorldPath(LevelResource.ROOT).resolve("data/" + DATA_NAME + ".dat");
            if (Files.exists(file)) {
                // Minecraft logs disk/decompression failures before our load callback can run.
                LOGGER.error("Existing colony file {} could not be read by Minecraft; sealing the registry. "
                    + "See the preceding saved-data error for the cause.", file);
                registry = sealed("Minecraft could not read the existing colony file.");
            } else {
                registry = new ColonyRegistry();
            }
            storage.set(DATA_NAME, registry);
        }
        try {
            for (ColonyData colony : List.copyOf(registry.colonies.values())) {
                colony.on(server);
                ColonyRuntime.settle(server, colony.works());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("A Folkways colony service failed to start; sealing the registry. "
                + "The colony file was not changed. Resolve the cause below, then reopen the world.", exception);
            registry = new ColonyRegistry(Optional.of(Component.literal(
                "A Folkways colony service failed to start. The colony file was not changed. "
                    + "The server log has the cause. The world can be reopened once the failure is resolved.")));
            storage.set(DATA_NAME, registry);
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                registry.rejectPlayer(player);
            }
        }
        return registry;
    }

    public Optional<ColonyData> find(UUID colonyId) {
        return Optional.ofNullable(colonies.get(colonyId));
    }

    public ColonyData mint(MinecraftServer server) {
        requireReadable();
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
        requireReadable();
        ColonyData colony = colonies.remove(colonyId);
        if (colony != null) {
            colony.claims().forgetAll(server);
            colony.works().closed(Closing.RAZED);
            ColonyWays.forget(colonyId);
        }
        razed.add(colonyId);
        setDirty();
    }

    // Whether any colony already holds this block or entity: each is held by one colony at most.
    boolean taken(Claimed what) {
        return colonies.values().stream().anyMatch(colony -> colony.claims().taken(what));
    }

    public Collection<ColonyData> colonies() {
        return Collections.unmodifiableCollection(colonies.values());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        requireReadable();
        tag.putInt("schemaVersion", DataVersion.CURRENT);
        return Writer.of(tag)
            .children("colonies", colonies.values(), colony -> colony.save(new CompoundTag()))
            .uuids("razed", razed)
            .tag();
    }

    static ColonyRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
        try {
            int stored = Required.integer(tag, "schemaVersion");
            tag = tag.copy();
            Upgrades.upgrade(Surface.COLONY_REGISTRY, tag, stored);
            Reader reader = Reader.of(tag);
            Required.list(tag, "colonies", Tag.TAG_COMPOUND);
            Required.list(tag, "razed", Tag.TAG_INT_ARRAY);
            ColonyRegistry registry = new ColonyRegistry(Optional.empty());
            registry.razed.addAll(reader.requiredUuids("razed"));
            int index = 0;
            for (Reader row : reader.children("colonies")) {
                ColonyData colony = ColonyData.load(row).orElse(null);
                if (colony == null) {
                    throw new IllegalArgumentException("Unreadable colony row " + index);
                }
                if (registry.colonies.containsKey(colony.colonyId())) {
                    throw new IllegalArgumentException("Duplicate colony id at row " + index);
                }
                registry.adopt(colony);
                index++;
            }
            if (stored != DataVersion.CURRENT) {
                registry.setDirty();
            }
            return registry;
        } catch (RuntimeException exception) {
            LOGGER.error("Folkways colony save is unreadable or unsupported; sealing the registry. "
                + "Nothing will be written over folkways_colonies.dat.", exception);
            return sealed(exception.getMessage());
        }
    }

    private void adopt(ColonyData colony) {
        colony.setDirtyCallback(this::setDirty);
        colonies.put(colony.colonyId(), colony);
    }
}
