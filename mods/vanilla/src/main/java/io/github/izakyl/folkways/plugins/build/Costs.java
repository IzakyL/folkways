package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class Costs {

    /**
     * A cell's requirement as the schematicannon reckons it. An invalid one is skipped by the cannon altogether; an
     * unsupported one is valid but asks for something the colony cannot represent, such as a tool worn down.
     */
    public record Quote(Optional<List<Need>> needs, boolean invalid) {

        public static Quote of(List<Need> needs) {
            return new Quote(Optional.of(List.copyOf(needs)), false);
        }

        public static Quote unsupported() {
            return new Quote(Optional.empty(), false);
        }

        public static Quote skipped() {
            return new Quote(Optional.empty(), true);
        }
    }

    private Costs() {
    }

    public static Need need(ItemStack stack) {
        return new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }

    public static Need need(Item item, int count) {
        return new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)), count);
    }

    /** Strict schematic requirements retain the complete component identity. */
    public static Quote strict(ItemStack stack) {
        return Quote.of(List.of(new Need(ItemSpec.exact(stack), stack.getCount())));
    }

    /** A fluid source is poured from its bucket: a step of the colony's own, not one of the cannon's. */
    static Optional<List<Need>> poured(BlockState fluid) {
        FluidState state = fluid.getFluidState();
        if (state.isEmpty() || !state.isSource()) {
            return Optional.empty();
        }
        Item bucket = state.getType().getBucket();
        return bucket == Items.AIR ? Optional.empty() : Optional.of(List.of(need(bucket, 1)));
    }
}
