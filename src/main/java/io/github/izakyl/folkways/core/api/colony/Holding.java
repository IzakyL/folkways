package io.github.izakyl.folkways.core.api.colony;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

// One thing a colony holds, and the id it was handed over under: whoever answers for that id says what it is for.
public record Holding(UUID id, ResourceLocation owner, Held what) {

    public Holding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(what, "what");
    }

    public Holding moved(Held now) {
        return new Holding(id, owner, now);
    }

    // Hears what its colony takes up and lets go under the ids it watches.
    public interface Watch {

        default void held(Holding holding) {
        }

        default void released(Holding holding, Release why) {
        }
    }
}
