package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Work standing still where it is for a reason that lasts until someone changes the world: the plan could not grow it,
 * or a resident met the same refusal there more than once. {@code goods} names what the work went short of, when it
 * did; {@code doing} and {@code about} what the work is and what it is done to, as its spec names them.
 */
public record Stall(WorldPos where, RefusalKind why, Optional<ItemSpec> goods, ResourceLocation doing,
                   Optional<String> about) {
}
