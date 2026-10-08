package io.github.izakyl.folkways.core.api.colony;

import java.util.Optional;
import net.minecraft.world.entity.Entity;

// Why a colony stopped holding something.
public enum Release {

    // Someone with the colony's book let it go.
    LET_GO,

    // It is no longer there to hold: the block was broken, the entity discarded.
    GONE,

    // The body died: it is past being told it left.
    DIED;

    public static Optional<Release> fromWorld(Entity.RemovalReason reason) {
        if (reason == null) {
            return Optional.empty();
        }
        return switch (reason) {
            case KILLED -> Optional.of(DIED);
            case DISCARDED -> Optional.of(GONE);
            case UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER, CHANGED_DIMENSION -> Optional.empty();
        };
    }
}
