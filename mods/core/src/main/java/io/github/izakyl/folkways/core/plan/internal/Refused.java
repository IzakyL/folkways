package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.Optional;

public record Refused(Optional<ItemSpec> asked, RefusalKind why) {
}
