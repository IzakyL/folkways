package io.github.izakyl.folkways.core.shell.mekanism;

import mekanism.common.content.qio.IQIODriveItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
public final class QioFixtures {

    static Item SMALL;
    static Item LARGE;

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.ITEM, registry -> {
            SMALL = new Drive(10, 1);
            LARGE = new Drive(1000, 2);
            registry.register(ResourceLocation.parse("folkways_core:qio_small"), SMALL);
            registry.register(ResourceLocation.parse("folkways_core:qio_large"), LARGE);
        });
    }

    private static final class Drive extends Item implements IQIODriveItem {

        private final long items;
        private final int types;

        Drive(long items, int types) {
            super(new Item.Properties().stacksTo(1));
            this.items = items;
            this.types = types;
        }

        @Override
        public long getCountCapacity(ItemStack stack) {
            return items;
        }

        @Override
        public int getTypeCapacity(ItemStack stack) {
            return types;
        }
    }
}
