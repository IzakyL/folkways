package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Facings {

    public interface Source {
        Optional<Facing> of(Colony colony, ResourceLocation owner);
        List<Facing> all(Colony colony);
    }

    private static Source source = new Source() {
        public Optional<Facing> of(Colony colony, ResourceLocation owner) { return Optional.empty(); }
        public List<Facing> all(Colony colony) { return List.of(); }
    };

    private Facings() {
    }

    public static void install(Source installed) {
        source = installed;
    }

    public static Optional<Facing> of(Colony colony, ResourceLocation owner) {
        return source.of(colony, owner);
    }

    public static List<Facing> all(Colony colony) {
        return source.all(colony);
    }
}
