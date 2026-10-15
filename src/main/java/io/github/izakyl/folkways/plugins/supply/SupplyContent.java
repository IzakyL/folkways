package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SupplyContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "supply");
    public static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath("folkways", "creative_supply_box");
    public static final ResourceLocation TAKING = ResourceLocation.fromNamespaceAndPath("folkways", "taking_supplies");
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FolkwaysMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FolkwaysMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(
        Registries.BLOCK_ENTITY_TYPE, FolkwaysMod.MOD_ID);
    public static final DeferredBlock<SupplyBlock> BLOCK = BLOCKS.register("creative_supply_box",
        () -> new SupplyBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL).strength(-1, 3600000)));
    public static final DeferredItem<Item> ITEM = ITEMS.register("creative_supply_box",
        () -> new SupplyItem(BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SupplyBox>> BLOCK_ENTITY =
        ENTITIES.register("creative_supply_box", () -> BlockEntityType.Builder.of(SupplyBox::new, BLOCK.get()).build(null));

    private SupplyContent() {
    }

    public static void install(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener(Declaring.class, SupplyContent::declare);
        modBus.addListener(Registering.class, SupplyContent::enroll);
        modBus.addListener(SupplyNetwork::register);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) event.accept(ITEM.get());
        });
    }

    private static void declare(Declaring event) {
        event.colony(ID, scope -> {
            SupplyPresence state = new SupplyPresence(scope.colony(), scope.server());
            scope.share(state);
            scope.watch(SOURCE, state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    private static void enroll(Registering event) {
        event.facing(ID, colony -> colony.service(ID, SupplyPresence.class).map(state -> state));
        event.enrollment(ID, new Enrollment(List.of(new Delegation(SOURCE,
            new Shape.Block(state -> state.is(BLOCK.get())))), Schema.none(),
            List.of(PageKind.boarded(ID, "folkways.page.supply", SOURCE))));
    }
}
