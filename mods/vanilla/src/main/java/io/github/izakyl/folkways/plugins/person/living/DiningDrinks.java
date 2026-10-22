package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** Optional drinks accompany real table meals; they never count as food or pocket rations. */
public final class DiningDrinks {
    public interface Drink {
        boolean matches(ItemStack stack);
        /** Run native effects on one detached serving and return its remaining container.
         * Consume the serving before applying effects. Spawned containers are captured by the caller.
         * An adapter must not return the original drink as a byproduct. */
        ItemStack finishUsing(ServerLevel level, Mob diner, ItemStack serving);
    }
    /** Native drink cabinets also expose the same inventory through the core Containers adapter. */
    public interface Supply {
        boolean matches(ServerLevel level, BlockPos at);
        Container inventory(ServerLevel level, BlockPos at);
    }
    private static final Map<ResourceLocation, Supply> SUPPLIES = new LinkedHashMap<>();
    public static synchronized void registerSupply(ResourceLocation id, Supply supply) {
        if (SUPPLIES.putIfAbsent(id, supply) != null) { throw new IllegalArgumentException("duplicate dining drink supply: " + id); }
    }
    static Optional<Container> supply(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) { return Optional.empty(); }
        return SUPPLIES.values().stream().filter(supply -> supply.matches(level, at))
            .map(supply -> supply.inventory(level, at)).filter(java.util.Objects::nonNull).findFirst();
    }

    private static final Map<ResourceLocation, Drink> DRINKS = new LinkedHashMap<>();
    private DiningDrinks() { }

    public static synchronized void register(ResourceLocation id, Drink drink) {
        if (DRINKS.putIfAbsent(id, drink) != null) { throw new IllegalArgumentException("duplicate dining drink: " + id); }
    }

    public static boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && DRINKS.values().stream().anyMatch(drink -> drink.matches(stack));
    }

    static List<ItemStack> consume(ServerLevel level, Mob diner, Container display, int slot) {
        ItemStack stack = display.getItem(slot);
        Drink drink = DRINKS.values().stream().filter(candidate -> candidate.matches(stack)).findFirst().orElse(null);
        if (stack.isEmpty() || drink == null) { return List.of(); }
        ItemStack serving = display.removeItem(slot, 1);
        if (serving.isEmpty()) { return List.of(); }
        display.setChanged();
        ItemDrops.Captured<ItemStack> used;
        try {
            used = ItemDrops.capture(level, diner.getBoundingBox().inflate(1), () -> drink.finishUsing(level, diner, serving));
        } catch (ItemDrops.CaptureFailure broken) {
            if (!serving.isEmpty()) {
                ItemStack back = Containers.insert(display, serving);
                if (!back.isEmpty()) { diner.spawnAtLocation(back); }
                return List.of();
            }
            used = new ItemDrops.Captured<>(ItemStack.EMPTY, broken.items());
        }
        // Integrations must consume their detached serving. Refuse a broken adapter
        // without publishing its returned/dropped byproducts or completion history.
        if (!serving.isEmpty()) {
            ItemStack current = display.getItem(slot);
            if (current.isEmpty()) { display.setItem(slot, serving); }
            else {
                ItemStack back = Containers.insert(display, serving);
                if (!back.isEmpty()) { diner.spawnAtLocation(back); }
            }
            display.setChanged();
            return List.of();
        }
        List<ItemStack> left = new ArrayList<>(used.items());
        if (!used.result().isEmpty()) { left.add(used.result()); }
        var history = diner.getPersistentData();
        history.putLong("folkways:last_table_drink", level.getGameTime());
        history.putInt("folkways:table_drinks", history.getInt("folkways:table_drinks") + 1);
        return List.copyOf(left);
    }
}
