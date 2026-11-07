package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public interface Conveyance {

    default void setOut(ServerLevel level, Mob body, Set<WorldPos> cells) {
    }

    Going step(ServerLevel level, Mob body, Set<WorldPos> cells);

    default Optional<Doing> doing() {
        return Optional.empty();
    }

    void release(ServerLevel level, Mob body);
}
