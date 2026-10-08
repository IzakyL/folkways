package io.github.izakyl.folkways.front.engine.registry;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.BookGestures;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FolkwaysDataComponents {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        FolkwaysMod.MOD_ID
    );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> COLONY_ID = COMPONENTS.register(
        "colony_id",
        () -> DataComponentType.<UUID>builder()
            .persistent(UUIDUtil.CODEC)
            .networkSynchronized(UUIDUtil.STREAM_CODEC)
            .build()
    );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Shape.Gesture>> BOOK_GESTURE =
        COMPONENTS.register(
            "book_gesture",
            () -> DataComponentType.<Shape.Gesture>builder()
                .persistent(BookGestures.CODEC)
                .networkSynchronized(BookGestures.STREAM_CODEC)
                .build()
        );

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> FILTER_TAG =
        COMPONENTS.register(
            "filter_tag",
            () -> DataComponentType.<ResourceLocation>builder()
                .persistent(ResourceLocation.CODEC)
                .networkSynchronized(ResourceLocation.STREAM_CODEC)
                .build()
        );

    private FolkwaysDataComponents() {
    }

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}
