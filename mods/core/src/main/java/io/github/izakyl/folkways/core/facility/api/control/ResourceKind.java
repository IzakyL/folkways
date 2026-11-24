package io.github.izakyl.folkways.core.facility.api.control;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A kind of resource a {@link Port} moves, with the amount type its transfers use. Items, fluids and energy are
 * Core's; an adapter may add its own (mana, chemicals) and register it with {@code Ports.kind}.
 */
public record ResourceKind<R>(ResourceLocation id, Class<R> amount, Predicate<R> empty, UnaryOperator<R> copy) {

    public static final ResourceKind<ItemStack> ITEM = new ResourceKind<>(core("item"), ItemStack.class,
        ItemStack::isEmpty, ItemStack::copy);
    public static final ResourceKind<FluidStack> FLUID = new ResourceKind<>(core("fluid"), FluidStack.class,
        FluidStack::isEmpty, FluidStack::copy);
    /** Forge energy, in FE. */
    public static final ResourceKind<Integer> ENERGY = new ResourceKind<>(core("energy"), Integer.class,
        amount -> amount <= 0, amount -> amount);

    public ResourceKind {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(empty, "empty");
        Objects.requireNonNull(copy, "copy");
    }

    public boolean isEmpty(R amount) {
        return empty.test(amount);
    }

    private static ResourceLocation core(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", path);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResourceKind<?> kind && kind.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
