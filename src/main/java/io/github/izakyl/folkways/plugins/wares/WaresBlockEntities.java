package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WaresBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FolkwaysMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WorkSiteBlockEntity>> WORK_SITE =
        BLOCK_ENTITIES.register("work_site", () -> BlockEntityType.Builder
            .of(WorkSiteBlockEntity::new,
                WorkSiteBlocks.WITHOUT_VANILLA_BLOCK_ENTITY.toArray(new Block[0]))
            .build(null));

    private WaresBlockEntities() {
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
