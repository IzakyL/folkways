package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Doing;
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
