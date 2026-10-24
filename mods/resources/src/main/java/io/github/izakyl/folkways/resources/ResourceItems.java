package io.github.izakyl.folkways.resources;

import java.util.List;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ResourceItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("folkways");
    public static final DeferredItem<Item> WOOD = ITEMS.registerSimpleItem("wood_resource");
    public static final DeferredItem<Item> STONE = ITEMS.registerSimpleItem("stone_resource");
    public static final DeferredItem<Item> SAPLING = ITEMS.registerSimpleItem("sapling_resource");
    public static final DeferredItem<Item> SEED = ITEMS.registerSimpleItem("seed_resource");
    public static final List<DeferredItem<Item>> ALL = List.of(WOOD, STONE, SAPLING, SEED);

    private ResourceItems() { }

    public static void register(IEventBus modBus) { ITEMS.register(modBus); }
}
