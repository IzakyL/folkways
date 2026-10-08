package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.Optional;
import java.util.UUID;

/**
 * A material edge: goods going into the work `to` uses them, from the work that makes or moves them, or from where
 * they lie now. Work feeding work comes before it, as a logic edge would; unlike one, what it feeds is the plan's to
 * feed again some other way when it goes.
 */
public record Flow(From from, UUID to, ItemSpec goods, long count) {

    public sealed interface From {

        // Made, or brought, by this work.
        record Work(UUID node) implements From {
        }

        // In this store now.
        record Lying(Stash where) implements From {
        }

        // In this resident's pack now.
        record Carried(UUID hand) implements From {
        }
    }

    public static Flow from(UUID node, UUID to, ItemSpec goods, long count) {
        return new Flow(new From.Work(node), to, goods, count);
    }

    // The work it comes after, when it comes from work.
    public Optional<UUID> after() {
        return from instanceof From.Work(UUID node) ? Optional.of(node) : Optional.empty();
    }
}
