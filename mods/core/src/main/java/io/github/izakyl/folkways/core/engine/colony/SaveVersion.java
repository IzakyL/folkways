package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

final class SaveVersion {
    static final int CURRENT = 1;

    private static final int FIRST = 1;

    private static final String KEY = "schemaVersion";

    private SaveVersion() {
    }

    static void write(CompoundTag tag) {
        Writer.of(tag).integer(KEY, CURRENT);
    }

    static Optional<Integer> of(Reader reader) {
        return reader.integer(KEY);
    }

    static boolean isReadable(Reader reader) {
        return of(reader).orElse(FIRST) <= CURRENT;
    }
}
