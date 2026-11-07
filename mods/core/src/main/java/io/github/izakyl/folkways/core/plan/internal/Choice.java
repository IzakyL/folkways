package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.UUID;

record Choice(UUID open, ItemSpec picked, Object source) {
}
