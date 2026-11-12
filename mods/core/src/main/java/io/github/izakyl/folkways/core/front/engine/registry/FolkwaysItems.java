package io.github.izakyl.folkways.core.front.engine.registry;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.upgrade.RegistryAliases;
import io.github.izakyl.folkways.core.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.core.front.engine.item.TagFilterItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FolkwaysItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FolkwaysCoreMod.MOD_ID);

    public static final DeferredItem<ColonyBookItem> COLONY_BOOK = ITEMS.registerItem(
        "colony_book",
        ColonyBookItem::new,
        new Item.Properties().stacksTo(1).fireResistant()
    );

    public static final DeferredItem<TagFilterItem> TAG_FILTER = ITEMS.registerItem(
        "tag_filter",
        TagFilterItem::new,
        new Item.Properties().stacksTo(1)
    );

    // Identity token for virtual network recipes; deliberately absent from creative tabs.
    public static final DeferredItem<Item> NETWORK_PATTERN = ITEMS.registerItem(
        "network_pattern", Item::new, new Item.Properties().stacksTo(1));

    private FolkwaysItems() {
    }

    public static void register(IEventBus modBus) {
        RegistryAliases.register(ITEMS, modBus);
    }
}
