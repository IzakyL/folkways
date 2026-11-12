package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.wares.mixin.FurnaceAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

final class FuelNode extends CraftNode {

    private final ItemSpec fuel;
    private final Runnable lit;
    private final LongSupplier cooking;
    private final RecipeType<? extends AbstractCookingRecipe> recipeType;

    FuelNode(UUID id, WorldPos at, Block block, Stances stances, ItemSpec fuel, long count, Runnable lit) {
        this(id, at, block, stances, fuel, count, () -> Long.MAX_VALUE, lit);
    }

    FuelNode(UUID id, WorldPos at, Block block, Stances stances, ItemSpec fuel, long count,
             LongSupplier cooking, Runnable lit) {
        super(specOf(id, at, stances, Workload.Once.of(Stations.LABOR_TICKS, WaresContent::haste))
                .needs(new Need(fuel, count))
                .doing(WaresContent.FUELING, fuel)
                .done(),
            at, block);
        this.fuel = fuel;
        this.lit = lit;
        this.cooking = cooking;
        recipeType = Stations.COOKERS.get(block);
    }

    @Override
    public List<Need> recipeInputs() {
        return List.of();
    }

    @Override
    public boolean ready(ServerLevel level) {
        Optional<Container> machine = machine(level);
        return standing(level) && machine.isPresent()
            && (stocked(machine.get()) >= cooking.getAsLong()
                || Machines.takes(machine.get(), Machines.FUEL_SLOT, fuel));
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        Optional<Container> machine = machine(level);
        if (machine.isEmpty()) {
            return Outcome.failed(WaresRefusal.NOT_A_STATION);
        }
        List<ItemStack> rejected = new ArrayList<>();
        int offered = 0;
        int loaded = 0;
        long wanting = Math.max(0, cooking.getAsLong() - stocked(machine.get()));
        for (ItemStack drawn : who.supplied()) {
            // Separate accepted batches keep their original fuel steps. Another hand may have stoked the
            // cooker since they were priced, so return supplies beyond the whole booked queue's fuel budget.
            int burns = drawn.getBurnTime(recipeType);
            int count = burns <= 0 ? drawn.getCount()
                : (int) Math.min(drawn.getCount(), wanting <= 0 ? 0 : 1 + (wanting - 1) / burns);
            ItemStack unused = drawn.copyWithCount(drawn.getCount() - count);
            if (!unused.isEmpty()) {
                rejected.add(unused);
            }
            if (count <= 0) {
                continue;
            }
            offered += count;
            ItemStack left = Machines.insertIntoSlot(machine.get(), Machines.FUEL_SLOT, drawn.copyWithCount(count));
            loaded += count - left.getCount();
            wanting = Math.max(0, wanting - (long) (count - left.getCount()) * burns);
            if (!left.isEmpty()) {
                rejected.add(left);
            }
        }
        if (offered > 0 && loaded == 0) {
            // The one fuel slot holds another fuel, put there by another hand: the cooker is stoked, and what was
            // brought is handed back. Waiting for the slot would be waiting for the cooker to burn it all.
            ItemStack held = machine.get().getItem(Machines.FUEL_SLOT);
            if (!held.isEmpty() && held.getCount() < held.getMaxStackSize()
                    && rejected.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, held))) {
                lit.run();
                return new Outcome.Done(worked(), List.of(), Optional.empty(), rejected);
            }
            return Outcome.failed(WaresRefusal.STATION_FULL);
        }
        lit.run();
        return new Outcome.Done(worked(), List.of(), earned(), rejected);
    }

    private long stocked(Container machine) {
        ItemStack held = machine.getItem(Machines.FUEL_SLOT);
        long burning = machine instanceof FurnaceAccessor furnace ? Math.max(0, furnace.folkways$burns()) : 0;
        return burning + (long) held.getBurnTime(recipeType) * held.getCount();
    }
}
