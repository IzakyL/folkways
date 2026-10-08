package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

final class Networks {

    private static final String TAG_NETWORKS = "networks";

    private final Set<UUID> recognised = new LinkedHashSet<>();

    boolean holds(UUID network) {
        return recognised.contains(network);
    }

    List<UUID> all() {
        return List.copyOf(recognised);
    }

    boolean take(UUID network) {
        return recognised.add(network);
    }

    boolean drop(UUID network) {
        return recognised.remove(network);
    }

    void clear() {
        recognised.clear();
    }

    CompoundTag save() {
        return Writer.of().uuids(TAG_NETWORKS, recognised).tag();
    }

    void load(Reader reader) {
        recognised.clear();
        recognised.addAll(reader.uuids(TAG_NETWORKS));
    }
}
