package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// Goods wanted somewhere: a container, or the pack of the resident a site names. The core decides how they
// get there and tells `arrived` how many did once the last of them is put down.
public record Delivery(NodeSpec spec, Need goods, LongConsumer arrived, Optional<Source> source) implements Intent {

    // An exact stack collected from an exposed slot, rather than goods drawn from general stock.
    public record Source(WorldPos at, Stances stances, Optional<Direction> side, int slot, ItemStack stack,
                         BlockState state, BlockEntity entity) {
        public Source {
            if (slot < 0 || stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("a collection needs one stack in an exposed slot");
            }
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() { return stack.copy(); }
    }

    public Delivery(NodeSpec spec, Need goods, LongConsumer arrived) {
        this(spec, goods, arrived, Optional.empty());
    }

    public static Delivery from(UUID id, ResourceLocation owner, Source source, WorldPos into, Stances stances,
                                LongConsumer arrived) {
        return new Delivery(NodeSpec.of(id, owner, new WorkSite.AtBlock(into), stances, Workload.Once.of(1)).done(),
            new Need(Goods.specOf(source.stack()), source.stack().getCount(), true), arrived, Optional.of(source));
    }

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
