package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Optional;

public record Refused(Optional<ItemSpec> asked, RefusalKind why) {
}
