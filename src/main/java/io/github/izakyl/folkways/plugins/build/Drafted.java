package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

record Drafted(UUID order, ResourceLocation pattern, Hint hint) {
    private static final String TAG_ORDER = "order";
    private static final String TAG_PATTERN = "pattern";
    private static final String TAG_HINT = "hint";

    Drafted {
        Objects.requireNonNull(order);
        Objects.requireNonNull(pattern);
        Objects.requireNonNull(hint);
    }

    CompoundTag save() {
        return Writer.of()
            .uuid(TAG_ORDER, order)
            .id(TAG_PATTERN, pattern)
            .blob(TAG_HINT, hint.save())
            .tag();
    }

    static Optional<Drafted> load(Reader reader) {
        Optional<UUID> order = reader.uuid(TAG_ORDER);
        Optional<ResourceLocation> pattern = reader.id(TAG_PATTERN);
        Optional<Hint> hint = reader.blob(TAG_HINT).flatMap(Hint::load);
        if (order.isEmpty() || pattern.isEmpty() || hint.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Drafted(order.get(), pattern.get(), hint.get()));
    }
}
