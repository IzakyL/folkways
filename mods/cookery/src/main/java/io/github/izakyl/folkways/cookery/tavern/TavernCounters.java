package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import io.github.izakyl.folkways.plugins.person.living.DiningDrinks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TavernCounters {
    private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "folkways_cookery");
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TavernCounterBlockEntity>> COUNTER = TYPES.register("tavern_counter",
        () -> BlockEntityType.Builder.of(TavernCounterBlockEntity::new, ModBlocks.BAR_COUNTER.get()).build(null));
    private TavernCounters() { }
    public static void register(IEventBus bus) { TYPES.register(bus); }
    public static void supply() {
        DiningDrinks.registerSupply(ResourceLocation.fromNamespaceAndPath("folkways", "tavern_counter"), new DiningDrinks.Supply() {
            public boolean matches(ServerLevel level, BlockPos at) { return level.getBlockEntity(at) instanceof TavernCounterBlockEntity; }
            public Container inventory(ServerLevel level, BlockPos at) { return level.getBlockEntity(at) instanceof TavernCounterBlockEntity counter ? counter : null; }
        });
    }
}
