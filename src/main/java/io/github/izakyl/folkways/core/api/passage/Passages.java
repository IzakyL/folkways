package io.github.izakyl.folkways.core.api.passage;

import io.github.izakyl.folkways.core.api.Ledger;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Passages {

    private static final Ledger<Passage> WAYS = new Ledger<>("a way across", Passage::id);

    private Passages() {
    }

    public static Passage register(Passage passage) {
        return WAYS.claim(passage);
    }

    public static List<ResourceLocation> ids() {
        return WAYS.ids();
    }

    public static List<Passage> all() {
        return WAYS.all();
    }

    public static Optional<Passage> of(ResourceLocation id) {
        return WAYS.of(id);
    }
}
