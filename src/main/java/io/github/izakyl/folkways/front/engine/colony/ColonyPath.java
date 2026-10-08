package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.PathView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class ColonyPath implements PathView {

    private static final String TAG_ID = "id";
    private static final String TAG_DELEGATION = "delegation";
    private static final String TAG_DIMENSION = "dim";
    private static final String TAG_POINTS = "points";
    private static final String TAG_SETTINGS = "settings";

    private final UUID id;
    private final ResourceLocation delegation;
    private final ResourceKey<Level> dimension;
    private final List<BlockPos> points;
    private final List<WorldPos> addresses;
    private final ColonySettings settings;

    private ColonyPath(UUID id, ResourceLocation delegation, ResourceKey<Level> dimension,
            List<BlockPos> points, List<WorldPos> addresses, ColonySettings settings) {
        this.id = Objects.requireNonNull(id, "id");
        this.delegation = Objects.requireNonNull(delegation, "delegation");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (points.size() < 2) {
            throw new IllegalArgumentException("a path of fewer than two points is not a path");
        }
        List<BlockPos> copied = new ArrayList<>(points.size());
        for (BlockPos point : points) {
            copied.add(point.immutable());
        }
        this.points = Collections.unmodifiableList(copied);
        this.addresses = List.copyOf(addresses);
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    // A held line, where the level its first point lies in stores it, with the settings drawn on it.
    public static Optional<ColonyPath> of(Holding holding, MinecraftServer server, ColonySettings settings) {
        if (!(holding.what() instanceof Held.Line line)) {
            return Optional.empty();
        }
        ServerLevel level = line.points().getFirst().level(server).orElse(null);
        if (level == null) {
            return Optional.empty();
        }
        List<BlockPos> points = new ArrayList<>(line.points().size());
        for (WorldPos point : line.points()) {
            Optional<BlockPos> stored = WorldSpaces.storage(level, point);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            points.add(stored.get());
        }
        return Optional.of(new ColonyPath(holding.id(), holding.owner(), level.dimension(), points,
            line.points(), settings));
    }

    public UUID id() {
        return id;
    }

    public ResourceLocation delegation() {
        return delegation;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public List<BlockPos> points() {
        return points;
    }

    public ColonySettings settings() {
        return settings;
    }

    public WorldPos at(BlockPos point) {
        int index = points.indexOf(point);
        return index >= 0 && index < addresses.size() ? addresses.get(index) : WorldPos.of(dimension, point);
    }

    public CompoundTag save() {
        Writer writer = Writer.of()
            .uuid(TAG_ID, id)
            .id(TAG_DELEGATION, delegation)
            .id(TAG_DIMENSION, dimension.location());
        int[] packed = new int[points.size() * 3];
        int at = 0;
        for (BlockPos point : points) {
            packed[at++] = point.getX();
            packed[at++] = point.getY();
            packed[at++] = point.getZ();
        }
        return writer
            .intArray(TAG_POINTS, packed)
            .blob(TAG_SETTINGS, settings.save())
            .tag();
    }

    public static Optional<ColonyPath> load(Reader reader) {
        Optional<UUID> id = reader.uuid(TAG_ID);
        Optional<ResourceLocation> delegation = reader.id(TAG_DELEGATION);
        Optional<ResourceLocation> dimension = reader.id(TAG_DIMENSION);
        Optional<int[]> packed = reader.intArray(TAG_POINTS);
        if (id.isEmpty() || delegation.isEmpty() || dimension.isEmpty() || packed.isEmpty()) {
            return Optional.empty();
        }
        int[] coordinates = packed.get();
        if (coordinates.length < 6 || coordinates.length % 3 != 0) {
            return Optional.empty();
        }
        List<BlockPos> points = new ArrayList<>(coordinates.length / 3);
        for (int at = 0; at < coordinates.length; at += 3) {
            points.add(new BlockPos(coordinates[at], coordinates[at + 1], coordinates[at + 2]));
        }
        ColonySettings settings = reader.child(TAG_SETTINGS)
            .map(ColonySettings::load)
            .orElseGet(ColonySettings::empty);
        return Optional.of(new ColonyPath(id.get(), delegation.get(),
            ResourceKey.create(Registries.DIMENSION, dimension.get()), points, List.of(), settings));
    }
}
