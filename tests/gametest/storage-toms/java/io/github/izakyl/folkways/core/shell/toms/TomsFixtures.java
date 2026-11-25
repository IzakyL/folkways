package io.github.izakyl.folkways.core.shell.toms;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
public final class TomsFixtures {

    private static BlockEntityType<Inventory> type;

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, registry -> {
            for (String name : List.of("toms_unknown", "toms_allowed")) {
                registry.register(ResourceLocation.fromNamespaceAndPath("folkways_core", name), new Storage());
            }
        });
        event.register(Registries.BLOCK_ENTITY_TYPE, registry -> {
            Block[] blocks = List.of("toms_unknown", "toms_allowed").stream()
                .map(name -> BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("folkways_core", name)))
                .toArray(Block[]::new);
            type = BlockEntityType.Builder.of(Inventory::new, blocks).build(null);
            registry.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "toms_inventory"), type);
        });
    }

    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type, (entity, side) -> new InvWrapper(entity));
    }

    private static final class Storage extends Block implements EntityBlock {

        Storage() {
            super(BlockBehaviour.Properties.of());
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Inventory(pos, state);
        }
    }

    private static final class Inventory extends BlockEntity implements Container {

        private final SimpleContainer inventory = new SimpleContainer(3);

        Inventory(BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        @Override
        public int getContainerSize() {
            return inventory.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return inventory.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return inventory.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return inventory.removeItem(slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return inventory.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inventory.setItem(slot, stack);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            inventory.clearContent();
        }
    }
}
