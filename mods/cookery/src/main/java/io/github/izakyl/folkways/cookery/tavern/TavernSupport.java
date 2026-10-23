package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.item.DrinkBlockItem;
import io.github.izakyl.folkways.cookery.KitchenStations;
import io.github.izakyl.folkways.plugins.person.living.DiningDrinks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** Loaded only after the optional Tavern mod has been detected. */
public final class TavernSupport {
    private static boolean registered;
    private TavernSupport() { }
    public static synchronized void register() {
        if (registered) return;
        KitchenStations.register(new TavernStation.Pressing());
        KitchenStations.register(new TavernStation.Barrel());
        TavernCabinets.register();
        TavernGrapes.register();
        DiningDrinks.register(ResourceLocation.fromNamespaceAndPath("folkways", "tavern"), new DiningDrinks.Drink() {
            public boolean matches(ItemStack stack) { return stack.getItem() instanceof DrinkBlockItem; }
            public ItemStack finishUsing(ServerLevel level, Mob diner, ItemStack serving) {
                // Native Tavern applies the real quality-dependent effects and emits its empty bottle.
                // Its item consumption is Player-only; this detached, single serving belongs to a Mob.
                ItemStack actual = serving.copyWithCount(1);
                serving.shrink(1);
                actual.finishUsingItem(level, diner);
                return ItemStack.EMPTY;
            }
        });
        registered = true;
    }
}
