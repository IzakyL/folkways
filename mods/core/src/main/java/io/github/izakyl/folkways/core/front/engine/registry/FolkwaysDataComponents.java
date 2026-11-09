package io.github.izakyl.folkways.core.front.engine.registry;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.upgrade.RegistryAliases;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.engine.item.BookGestures;
import io.github.izakyl.folkways.core.upgrade.ComponentCodecs;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FolkwaysDataComponents {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        FolkwaysCoreMod.MOD_ID
    );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Shape.Gesture>> BOOK_GESTURE =
        COMPONENTS.register(
            "book_gesture",
            () -> DataComponentType.<Shape.Gesture>builder()
                .persistent(ComponentCodecs.bookGesture(BookGestures.CODEC))
                .networkSynchronized(BookGestures.STREAM_CODEC)
                .build()
        );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> FILTER_TAG =
        COMPONENTS.register(
            "filter_tag",
            () -> DataComponentType.<ResourceLocation>builder()
                .persistent(ComponentCodecs.filterTag(ResourceLocation.CODEC))
                .networkSynchronized(ResourceLocation.STREAM_CODEC)
                .build()
        );

    private FolkwaysDataComponents() {
    }

    public static void register(IEventBus modBus) {
        RegistryAliases.register(COMPONENTS, modBus);
    }
}
