package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.UUID;

public record Choice(UUID open, ItemSpec picked, Object source) {
}
