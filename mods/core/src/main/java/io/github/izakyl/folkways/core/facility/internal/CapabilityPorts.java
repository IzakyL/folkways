package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Ports for any block exposing NeoForge's item, fluid or energy block capabilities.
 */
final class CapabilityPorts {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "capabilities");

    private CapabilityPorts() {
    }

    static void install() {
        Ports.register(ResourceKind.ITEM, ID, Ports.CAPABILITIES, (level, pos) -> {
            List<Port<ItemStack>> ports = new ArrayList<>();
            PortIdentity identity = Ports.itemAt(level, pos).map(ItemPort::identity)
                .orElseGet(() -> PortIdentity.block(pos));
            for (Direction side : Direction.values()) {
                IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos.block(level), side);
                if (handler != null) {
                    ports.add(new Items(pos, side, handler, identity));
                }
            }
            return List.copyOf(ports);
        });
        Ports.register(ResourceKind.FLUID, ID, Ports.CAPABILITIES, (level, pos) ->
            Optional.ofNullable(level.getCapability(Capabilities.FluidHandler.BLOCK, pos.block(level), null))
                .<Port<FluidStack>>map(handler -> new Fluid(pos, handler)).stream().toList());
        Ports.register(ResourceKind.ENERGY, ID, Ports.CAPABILITIES, (level, pos) ->
            Optional.ofNullable(level.getCapability(Capabilities.EnergyStorage.BLOCK, pos.block(level), null))
                .<Port<Integer>>map(storage -> new Energy(pos, storage)).stream().toList());
    }

    private static boolean loaded(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level));
    }

    record Items(WorldPos anchor, Direction side, IItemHandler handler, PortIdentity identity)
            implements Port<ItemStack> {

        @Override
        public String part() {
            return Ports.part(ResourceKind.ITEM) + "/" + side.getName();
        }

        @Override
        public ResourceKind<ItemStack> resource() {
            return ResourceKind.ITEM;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return loaded(level, anchor);
        }

        @Override
        public List<ItemStack> contents(ServerLevel level) {
            List<ItemStack> stacks = new ArrayList<>();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
            }
            return List.copyOf(stacks);
        }

        @Override
        public java.util.Map<String, io.github.izakyl.folkways.core.facility.api.control.Reading> readings() {
            return java.util.Map.of("count", level -> valid(level)
                ? Optional.of(Value.of(contents(level).stream().mapToLong(ItemStack::getCount).sum()))
                : Optional.empty());
        }

        @Override
        public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
            ItemStack left = offered.copy();
            for (int slot = 0; slot < handler.getSlots() && !left.isEmpty(); slot++) {
                left = handler.insertItem(slot, left, simulate);
            }
            return offered.copyWithCount(offered.getCount() - left.getCount());
        }

        @Override
        public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
            int left = wanted.getCount();
            for (int slot = 0; slot < handler.getSlots() && left > 0; slot++) {
                if (ItemStack.isSameItemSameComponents(handler.getStackInSlot(slot), wanted)) {
                    left -= handler.extractItem(slot, left, simulate).getCount();
                }
            }
            return wanted.copyWithCount(wanted.getCount() - left);
        }
    }

    record Fluid(WorldPos anchor, IFluidHandler handler) implements Port<FluidStack> {

        @Override
        public ResourceKind<FluidStack> resource() {
            return ResourceKind.FLUID;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return loaded(level, anchor);
        }

        @Override
        public FluidStack deposit(ServerLevel level, Mob writer, FluidStack offered, boolean simulate) {
            if (offered.isEmpty()) {
                return FluidStack.EMPTY;
            }
            int filled = handler.fill(offered.copy(), action(simulate));
            return filled <= 0 ? FluidStack.EMPTY : offered.copyWithAmount(filled);
        }

        @Override
        public FluidStack draw(ServerLevel level, Mob writer, FluidStack wanted, boolean simulate) {
            return wanted.isEmpty() ? FluidStack.EMPTY : handler.drain(wanted.copy(), action(simulate));
        }

        private static IFluidHandler.FluidAction action(boolean simulate) {
            return simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
        }
    }

    record Energy(WorldPos anchor, IEnergyStorage storage) implements Port<Integer> {

        @Override
        public ResourceKind<Integer> resource() {
            return ResourceKind.ENERGY;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return loaded(level, anchor);
        }

        @Override
        public Integer deposit(ServerLevel level, Mob writer, Integer offered, boolean simulate) {
            return offered <= 0 || !storage.canReceive() ? 0 : storage.receiveEnergy(offered, simulate);
        }

        @Override
        public Integer draw(ServerLevel level, Mob writer, Integer wanted, boolean simulate) {
            return wanted <= 0 || !storage.canExtract() ? 0 : storage.extractEnergy(wanted, simulate);
        }
    }
}
