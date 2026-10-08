package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.plugins.wares.mixin.FurnaceAccessor;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

// What a cooker has in it now, told in pictures: the fuel it burns, and the load it cooks turning into what it makes.
final class Hearths {

    private Hearths() {
    }

    // The fuel in its slot; where there is none, a cross and the colony's fuel.
    static Sentence fired(ServerLevel level, BlockPos at, Optional<ItemSpec> fuel) {
        Optional<Container> machine = cooker(level, at);
        if (machine.isEmpty()) {
            return Sentence.EMPTY;
        }
        ItemStack held = machine.get().getItem(Machines.FUEL_SLOT);
        if (!held.isEmpty()) {
            return Sentence.of(Sentence.doing(WaresContent.FUELING), Sentence.ware(held));
        }
        return fuel.flatMap(spec -> Goods.members(spec).stream().findFirst())
            .map(item -> Sentence.of(Sentence.glyph("lacks"), ware(item, 0)))
            .orElse(Sentence.EMPTY);
    }

    // The load in the input slot, an arrow, and what it cooks into; nothing while the cooker has no load.
    @SuppressWarnings("unchecked")
    static Sentence cooking(ServerLevel level, BlockPos at) {
        Optional<Container> machine = cooker(level, at);
        RecipeType<? extends AbstractCookingRecipe> type = Stations.COOKERS.get(level.getBlockState(at).getBlock());
        if (machine.isEmpty() || type == null) {
            return Sentence.EMPTY;
        }
        ItemStack load = machine.get().getItem(Machines.INPUT_SLOT);
        if (load.isEmpty()) {
            return Sentence.EMPTY;
        }
        SingleRecipeInput input = new SingleRecipeInput(load);
        return level.getRecipeManager()
            .getRecipeFor((RecipeType<AbstractCookingRecipe>) type, input, level)
            .map(holder -> holder.value().assemble(input, level.registryAccess()))
            .filter(made -> !made.isEmpty())
            .map(made -> Sentence.turning(List.of(ware(load.getItem(), load.getCount())),
                List.of(ware(made.getItem(), 0))))
            .orElse(Sentence.EMPTY);
    }

    // How far through its current item the cooker is, while it has a load to cook.
    static Optional<Line> progress(ServerLevel level, BlockPos at) {
        Optional<Container> machine = cooker(level, at);
        if (machine.isEmpty() || machine.get().getItem(Machines.INPUT_SLOT).isEmpty()
                || !(machine.get() instanceof FurnaceAccessor furnace) || furnace.folkways$cooks() <= 0) {
            return Optional.empty();
        }
        return Optional.of(Line.bar(furnace.folkways$cooked(), furnace.folkways$cooks()));
    }

    private static Sentence.Token.Ware ware(Item item, long count) {
        return new Sentence.Token.Ware(BuiltInRegistries.ITEM.getKey(item), count);
    }

    private static Optional<Container> cooker(ServerLevel level, BlockPos at) {
        return Machines.at(level, at).filter(machine -> machine.getContainerSize() > Machines.RESULT_SLOT);
    }
}
