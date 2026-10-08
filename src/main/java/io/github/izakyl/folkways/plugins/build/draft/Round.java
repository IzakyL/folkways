package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

public sealed interface Round {

    record Grew(Drawn.Ready ready, CompoundTag kept, boolean last) implements Round {

        public Grew {
            Objects.requireNonNull(ready, "ready");
            kept = Objects.requireNonNull(kept, "kept").copy();
        }
    }

    record Ended() implements Round {
    }

    record Stuck(Drawn.Refused refused) implements Round {

        public Stuck {
            Objects.requireNonNull(refused, "refused");
        }
    }
}
