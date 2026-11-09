package io.github.izakyl.folkways.vanilla.supply;

import io.github.izakyl.folkways.core.base.persist.upgrade.RegistryAliases;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
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
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "supply");
    public static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "creative_supply_box");
    public static final ResourceLocation TAKING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "taking_supplies");
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("folkways_vanilla");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("folkways_vanilla");
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(
        Registries.BLOCK_ENTITY_TYPE, "folkways_vanilla");
    public static final DeferredBlock<SupplyBlock> BLOCK = BLOCKS.register("creative_supply_box",
        () -> new SupplyBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL).strength(-1, 3600000)));
    public static final DeferredItem<Item> ITEM = ITEMS.register("creative_supply_box",
        () -> new SupplyItem(BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SupplyBox>> BLOCK_ENTITY =
        ENTITIES.register("creative_supply_box", () -> BlockEntityType.Builder.of(SupplyBox::new, BLOCK.get()).build(null));

    private SupplyContent() {
    }

    public static void install(IEventBus modBus) {
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) modBus.addListener(SupplyScene::declare);
        RegistryAliases.register(BLOCKS, modBus);
        RegistryAliases.register(ITEMS, modBus);
        RegistryAliases.register(ENTITIES, modBus);
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
