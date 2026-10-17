package io.github.izakyl.folkways.front.engine.registry;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.item.TagFilterItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FolkwaysItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FolkwaysMod.MOD_ID);

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

    private FolkwaysItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
