package io.github.izakyl.folkways.core.colony.api;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

// One thing a colony holds, and the id it was handed over under: whoever answers for that id says what it is for.
public record Claim(UUID id, ResourceLocation owner, Claimed what) {

    public Claim {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(what, "what");
    }

    public Claim moved(Claimed now) {
        return new Claim(id, owner, now);
    }

    // Hears what its colony takes up and lets go under the ids it watches.
    public interface Watch {

        default void held(Claim claim) {
        }

        default void released(Claim claim, ClaimRelease why) {
        }
    }
}
