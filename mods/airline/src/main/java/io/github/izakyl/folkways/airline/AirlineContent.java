package io.github.izakyl.folkways.airline;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class AirlineContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FolkwaysAirlineMod.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FolkwaysAirlineMod.ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(
        Registries.BLOCK_ENTITY_TYPE, FolkwaysAirlineMod.ID);
    public static final DeferredBlock<AirportTowerBlock> AIRPORT_TOWER = BLOCKS.register("airport_tower",
        () -> new AirportTowerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AirportTower>> AIRPORT_TOWER_ENTITY =
        ENTITIES.register("airport_tower", () -> BlockEntityType.Builder.of(AirportTower::new,
            AIRPORT_TOWER.get()).build(null));

    private AirlineContent() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register("airport_tower", () -> new BlockItem(AIRPORT_TOWER.get(), new Item.Properties()));
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
                event.accept(AIRPORT_TOWER.get());
            }
        });
    }
}
