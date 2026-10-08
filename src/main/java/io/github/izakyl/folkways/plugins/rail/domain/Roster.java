package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

final class Roster {

    private static final String TAG_TRAINS = "trains";

    private final Set<UUID> trains = new LinkedHashSet<>();

    boolean holds(UUID train) {
        return trains.contains(train);
    }

    List<UUID> all() {
        return List.copyOf(trains);
    }

    boolean take(UUID train) {
        return trains.add(train);
    }

    boolean drop(UUID train) {
        return trains.remove(train);
    }

    void clear() {
        trains.clear();
    }

    CompoundTag save() {
        return Writer.of().uuids(TAG_TRAINS, trains).tag();
    }

    void load(Reader reader) {
        trains.clear();
        trains.addAll(reader.uuids(TAG_TRAINS));
    }
}
