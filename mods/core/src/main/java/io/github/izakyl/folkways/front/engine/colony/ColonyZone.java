package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

// The part of a held area that lies in one level, where that level stores it, with the settings drawn on it.
public final class ColonyZone implements ZoneView {

    private final UUID id;
    private final ResourceLocation delegation;
    private final ResourceKey<Level> dimension;
    private final Map<BlockPos, WorldPos> addresses;
    private final ColonySettings settings;

    private ColonyZone(UUID id, ResourceLocation delegation, ResourceKey<Level> dimension,
            Map<BlockPos, WorldPos> addresses, ColonySettings settings) {
        this.id = Objects.requireNonNull(id, "id");
        this.delegation = Objects.requireNonNull(delegation, "delegation");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("a zone covering no cells is not a zone");
        }
        this.addresses = Collections.unmodifiableMap(new LinkedHashMap<>(addresses));
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static Optional<ColonyZone> in(Holding holding, ServerLevel level, ColonySettings settings) {
        if (!(holding.what() instanceof Held.Area area)) {
            return Optional.empty();
        }
        Map<BlockPos, WorldPos> here = new LinkedHashMap<>();
        for (WorldPos cell : area.covered()) {
            WorldSpaces.storage(level, cell).ifPresent(stored -> here.put(stored.immutable(), cell));
        }
        return here.isEmpty() ? Optional.empty()
            : Optional.of(new ColonyZone(holding.id(), holding.owner(), level.dimension(), here, settings));
    }

    public UUID id() {
        return id;
    }

    @Override
    public ResourceLocation delegation() {
        return delegation;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    @Override
    public Set<BlockPos> cells() {
        return addresses.keySet();
    }

    @Override
    public ColonySettings settings() {
        return settings;
    }

    public WorldPos at(BlockPos cell) {
        return addresses.getOrDefault(cell, WorldPos.of(dimension, cell));
    }
}
