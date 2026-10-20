package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.OwnerSave;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class Roster {

    private static final String TAG_TRAINS = "trains";

    // Version one keeps the original roster representation inside the owner's envelope.
    static final OwnerSave SAVE = new OwnerSave(RailContent.ID, List.of(payload -> payload), Roster::read);

    private final Set<UUID> trains = new LinkedHashSet<>();
    private CompoundTag saved = new CompoundTag();

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
        return Writer.of(saved.copy()).uuids(TAG_TRAINS, trains).tag();
    }

    void load(CompoundTag payload) {
        List<UUID> loaded = read(payload);
        trains.clear();
        trains.addAll(loaded);
        saved = payload.copy();
    }

    private static List<UUID> read(CompoundTag payload) {
        Tag trains = payload.get(TAG_TRAINS);
        if (trains != null && (!(trains instanceof ListTag list)
                || !list.isEmpty() && list.getElementType() != Tag.TAG_INT_ARRAY)) {
            throw new IllegalArgumentException("rail trains must be a list of UUIDs");
        }
        return Reader.of(payload).uuids(TAG_TRAINS);
    }
}
