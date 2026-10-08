package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.UUID;

public sealed interface Resource {

    record Lot(Stash where, ItemSpec spec) implements Resource {
    }

    record Space(Stash where, ItemSpec spec) implements Resource {
    }

    record Cargo(UUID resident, ItemSpec spec) implements Resource {
    }

    // Goods the node `by` will put into a pack, before anyone knows whose: claimed by the step that takes them out
    // again, and turned into that resident's Cargo once `by` is done.
    record Handed(UUID by, ItemSpec spec) implements Resource {
    }
}
