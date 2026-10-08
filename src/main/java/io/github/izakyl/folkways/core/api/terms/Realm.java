package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public sealed interface Realm {

    record Dimension(ResourceKey<Level> id) implements Realm {

        public Dimension {
            Objects.requireNonNull(id, "id");
        }
    }

    record Frame(UUID structure) implements Realm {

        public Frame {
            Objects.requireNonNull(structure, "structure");
        }
    }

    String TAG_DIMENSION = "dim";
    String TAG_FRAME = "frame";

    static Realm of(Level level) {
        return new Dimension(level.dimension());
    }

    static Realm of(ResourceKey<Level> dimension) {
        return new Dimension(dimension);
    }

    default boolean isDimension(ResourceKey<Level> which) {
        return this instanceof Dimension(ResourceKey<Level> id) && id.equals(which);
    }

    default CompoundTag save() {
        return switch (this) {
            case Dimension(ResourceKey<Level> id) -> Writer.of().id(TAG_DIMENSION, id.location()).tag();
            case Frame(UUID structure) -> Writer.of().uuid(TAG_FRAME, structure).tag();
        };
    }

    static Optional<Realm> load(Reader reader) {
        Optional<ResourceLocation> dimension = reader.id(TAG_DIMENSION);
        if (dimension.isPresent()) {
            return dimension.map(id -> new Dimension(ResourceKey.create(Registries.DIMENSION, id)));
        }
        return reader.uuid(TAG_FRAME).map(Frame::new);
    }
}
