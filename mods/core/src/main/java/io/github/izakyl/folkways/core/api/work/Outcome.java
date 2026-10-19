package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

public sealed interface Outcome {

    List<WorkNoise> noises();

    // Whatever the core handed over and the work did not use goes back into the worker's pack.
    record Done(List<WorkNoise> noises, List<ItemStack> made, Optional<Xp> xp, List<ItemStack> unused)
        implements Outcome {

        public Done {
            made = List.copyOf(made);
            unused = List.copyOf(unused);
        }

        public Done(List<WorkNoise> noises, List<ItemStack> made, Optional<Xp> xp) {
            this(noises, made, xp, List.of());
        }
    }

    record Failed(List<WorkNoise> noises, RefusalKind why) implements Outcome {
    }

    static Outcome done() {
        return new Done(List.of(), List.of(), Optional.empty());
    }

    static Outcome done(List<WorkNoise> noises) {
        return new Done(noises, List.of(), Optional.empty());
    }

    static Outcome failed(RefusalKind why) {
        return new Failed(List.of(), why);
    }
}
