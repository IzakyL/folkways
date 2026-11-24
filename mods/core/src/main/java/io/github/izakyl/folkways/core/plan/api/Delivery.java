package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.facility.api.control.PartEndpoint;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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
public record Delivery(NodeSpec spec, Need goods, LongConsumer arrived, Optional<Source> source,
                       Optional<PartEndpoint> destination) implements Intent {

    /** An exact stack collected from a known source. */
    public sealed interface Source {
        WorldPos at();

        Stances stances();

        ItemStack stack();
    }

    public record SlotSource(WorldPos at, Stances stances, Optional<Direction> side, int slot, ItemStack stack,
                             BlockState state, BlockEntity entity) implements Source {
        public SlotSource {
            if (slot < 0 || stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("a collection needs one stack in an exposed slot");
            }
            stack = stack.copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    public record PartSource(PartEndpoint endpoint, Stances stances, ItemStack stack) implements Source {
        public PartSource {
            if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("a collection needs one stack");
            }
            stack = stack.copy();
        }

        @Override
        public WorldPos at() {
            return endpoint.ref().at();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    public Delivery(NodeSpec spec, Need goods, LongConsumer arrived) {
        this(spec, goods, arrived, Optional.empty(), Optional.empty());
    }

    public static Delivery from(UUID id, ResourceLocation owner, Source source, WorldPos into, Stances stances,
                                LongConsumer arrived) {
        return new Delivery(NodeSpec.of(id, owner, new WorkSite.AtBlock(into), stances, Workload.Once.of(1)).done(),
            new Need(Goods.specOf(source.stack()), source.stack().getCount(), true), arrived, Optional.of(source), Optional.empty());
    }

    public Delivery {
        if (destination.filter(endpoint -> !endpoint.ref().at().equals(spec.site().where())).isPresent()) {
            throw new IllegalArgumentException("the delivery site must match the part");
        }
        if (goods.count() < 1) {
            throw new IllegalArgumentException("a delivery must contain goods");
        }
    }

    public static Delivery toPart(UUID id, ResourceLocation owner, PartEndpoint into, Stances stances,
                                  ItemSpec goods, long count, LongConsumer arrived) {
        WorkSite site = new WorkSite.AtBlock(into.ref().at());
        return new Delivery(NodeSpec.of(id, owner, site, stances, Workload.Once.of(1)).done(),
            new Need(goods, count, true), arrived, Optional.empty(), Optional.of(into));
    }

    public static Delivery to(UUID id, ResourceLocation owner, WorkSite into, Stances stances, ItemSpec goods,
                              long count, LongConsumer arrived) {
        return new Delivery(NodeSpec.of(id, owner, into, stances, Workload.Once.of(1)).done(),
            new Need(goods, count, true), arrived);
    }
}
