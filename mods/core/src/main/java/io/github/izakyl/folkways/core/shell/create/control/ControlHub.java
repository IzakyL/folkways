package io.github.izakyl.folkways.core.shell.create.control;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
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

public final class ControlHub {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, FolkwaysCoreMod.MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> CONNECTIONS =
        COMPONENTS.register("connections", () -> DataComponentType.<CompoundTag>builder()
            .persistent(CompoundTag.CODEC)
            .networkSynchronized(ByteBufCodecs.COMPOUND_TAG).build());
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FolkwaysCoreMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FolkwaysCoreMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(
        Registries.BLOCK_ENTITY_TYPE, FolkwaysCoreMod.MOD_ID);
    public static final DeferredBlock<TypewriterBlock> TYPEWRITER = BLOCKS.register("anonymous_typewriter",
        () -> new TypewriterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
    public static final DeferredBlock<TerminalBlock> TERMINAL = BLOCKS.register("control_terminal",
        () -> new TerminalBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Typewriter>> TYPEWRITER_ENTITY =
        ENTITIES.register("anonymous_typewriter", () -> BlockEntityType.Builder.of(Typewriter::new,
            TYPEWRITER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Terminal>> TERMINAL_ENTITY =
        ENTITIES.register("control_terminal", () -> BlockEntityType.Builder.of(Terminal::new,
            TERMINAL.get()).build(null));

    private ControlHub() {
    }

    public static void install(IEventBus bus) {
        HubPosts.install();
        ITEMS.register("anonymous_typewriter",
            () -> new TypewriterItem(TYPEWRITER.get(), new Item.Properties().stacksTo(1)));
        COMPONENTS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
                event.accept(TYPEWRITER.get());
            }
        });
    }
}
