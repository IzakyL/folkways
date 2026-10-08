package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.UUID;
import java.util.function.LongConsumer;
import net.minecraft.resources.ResourceLocation;

// Goods wanted somewhere: a container, or the pack of the resident a site names. The core decides how they
// get there and tells `arrived` how many did once the last of them is put down.
public record Delivery(NodeSpec spec, Need goods, LongConsumer arrived) implements Intent {

    public Delivery {
        if (goods.count() < 1) {
            throw new IllegalArgumentException("a delivery must contain goods");
        }
    }

    public static Delivery to(UUID id, ResourceLocation owner, WorkSite into, Stances stances, ItemSpec goods,
                              long count, LongConsumer arrived) {
        return new Delivery(NodeSpec.of(id, owner, into, stances, Workload.Once.of(1)).done(),
            new Need(goods, count, true), arrived);
    }
}
