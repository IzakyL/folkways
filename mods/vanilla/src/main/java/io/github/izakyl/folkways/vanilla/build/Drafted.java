package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

record Drafted(UUID order, ResourceLocation pattern, Hint hint, CompoundTag settings, Direction facing) {
    private static final String TAG_ORDER = "order";
    private static final String TAG_PATTERN = "pattern";
    private static final String TAG_HINT = "hint";

    Drafted {
        Objects.requireNonNull(order);
        Objects.requireNonNull(pattern);
        Objects.requireNonNull(hint);
        settings = Objects.requireNonNull(settings).copy();
        if (facing == null || facing.getAxis() == Direction.Axis.Y) {
            facing = Direction.NORTH;
        }
    }

    CompoundTag save() {
        return Writer.of()
            .uuid(TAG_ORDER, order)
            .id(TAG_PATTERN, pattern)
            .blob(TAG_HINT, hint.save())
            .blob("settings", settings)
            .string("facing", facing.getName())
            .tag();
    }

    static Optional<Drafted> load(Reader reader) {
        Optional<UUID> order = reader.uuid(TAG_ORDER);
        Optional<ResourceLocation> pattern = reader.id(TAG_PATTERN);
        Optional<Hint> hint = reader.blob(TAG_HINT).flatMap(Hint::load);
        Optional<CompoundTag> settings = reader.blob("settings");
        Optional<Direction> facing = reader.string("facing").map(Direction::byName)
            .filter(value -> value.getAxis().isHorizontal());
        if (order.isEmpty() || pattern.isEmpty() || hint.isEmpty() || settings.isEmpty() || facing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Drafted(order.get(), pattern.get(), hint.get(), settings.get().copy(), facing.get()));
    }
}
