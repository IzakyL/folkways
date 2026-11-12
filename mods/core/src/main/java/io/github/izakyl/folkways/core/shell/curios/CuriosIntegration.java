package io.github.izakyl.folkways.core.shell.curios;

import io.github.izakyl.folkways.core.equipment.api.GearGroup;
import io.github.izakyl.folkways.core.equipment.api.GearSlot;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.util.thread.EffectiveSide;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/** Uses the real Curios slot registry and capability, independently from Accessories. */
public final class CuriosIntegration {
    private static final ResourceLocation GROUP = ResourceLocation.parse("folkways_core:curios");

    private CuriosIntegration() {
    }

    public static void install() {
        GearSlots.register(new GearGroup(GROUP, Component.literal("Curios"), GearGroup.Placement.ACCESSORY, 0));
        GearSlots.dynamic(GROUP, CuriosIntegration::slots);
        GearSlots.drops(GROUP, body -> CuriosApi.getCuriosInventory(body.mob()).ifPresent(inventory -> {
            for (var slots : inventory.getCurios().values()) {
                for (var items : List.of(slots.getStacks(), slots.getCosmeticStacks())) {
                    for (int slot = 0; slot < items.getSlots(); slot++) {
                        ItemStack stack = items.getStackInSlot(slot);
                        if (!stack.isEmpty()) {
                            items.setStackInSlot(slot, ItemStack.EMPTY);
                            body.mob().spawnAtLocation(stack);
                        }
                    }
                }
            }
        }));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.bus.api.EventPriority.LOWEST,
            (net.neoforged.neoforge.event.OnDatapackSyncEvent event) -> {
                if (event.getPlayer() != null) {
                    return;
                }
                for (var level : event.getPlayerList().getServer().getAllLevels()) {
                    for (var entity : level.getAllEntities()) {
                        io.github.izakyl.folkways.core.resident.api.Bodies.of(entity).ifPresent(body ->
                            CuriosApi.getCuriosInventory(body.mob()).ifPresent(inventory -> {
                                var saved = inventory.writeTag();
                                inventory.getCurios().values().forEach(slots -> {
                                    for (int index = 0; index < slots.getSlots(); index++) {
                                        slots.getStacks().setStackInSlot(index, ItemStack.EMPTY);
                                        slots.getCosmeticStacks().setStackInSlot(index, ItemStack.EMPTY);
                                    }
                                });
                                inventory.readTag(saved);
                                inventory.reset();
                                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(body.mob(),
                                    new top.theillusivec4.curios.common.network.server.sync.SPacketSyncCurios(
                                        body.mob().getId(), inventory.getCurios()));
                            }));
                    }
                }
            });
    }

    private static List<GearSlot> slots() {
        List<GearSlot> slots = new ArrayList<>();
        boolean client = EffectiveSide.get() == LogicalSide.CLIENT;
        CuriosApi.getPlayerSlots(client).values().stream().sorted().forEach(type -> {
            for (int index = 0; index < type.getSize(); index++) {
                slots.add(new Slot(type, index));
            }
        });
        return List.copyOf(slots);
    }

    private record Slot(ISlotType type, int index) implements GearSlot {
        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("curios", type.getIdentifier() + "/" + index);
        }

        @Override
        public ResourceLocation group() {
            return GROUP;
        }

        @Override
        public int order() {
            return type.getOrder() * 1000 + index;
        }

        @Override
        public Component name() {
            Component name = Component.translatable("curios.identifier." + type.getIdentifier());
            return type.getSize() > 1 ? name.copy().append(" " + (index + 1)) : name;
        }

        @Override
        public boolean atlasIcon() {
            return true;
        }

        @Override
        public Optional<ResourceLocation> emptyIcon() {
            return Optional.of(type.getIcon());
        }

        private Optional<IDynamicStackHandler> handler(Body body) {
            return CuriosApi.getCuriosInventory(body.mob())
                .flatMap(items -> items.getStacksHandler(type.getIdentifier()))
                .map(items -> items.getStacks()).filter(items -> index < items.getSlots());
        }

        @Override
        public boolean fits(ItemStack stack) {
            // No resident is loaded in the profile picker: only framework predicates with a null wearer run here.
            return !stack.isEmpty() && CuriosApi.getItemStackSlots(stack, EffectiveSide.get() == LogicalSide.CLIENT)
                .keySet().stream().anyMatch(name -> name.equals(type.getIdentifier()) || name.equals("curio")
                    || type.getIdentifier().equals("curio"));
        }

        @Override
        public boolean fits(Body body, ItemStack stack) {
            // DynamicStackHandler includes predicates, ICurio.canEquip and CurioCanEquipEvent.
            return !stack.isEmpty() && handler(body).filter(items -> items.isItemValid(index, stack)).isPresent();
        }

        @Override
        public ItemStack worn(Body body) {
            return handler(body).map(items -> items.getStackInSlot(index)).orElse(ItemStack.EMPTY);
        }

        @Override
        public boolean removable(Body body) {
            return handler(body).filter(items -> !items.extractItem(index, 1, true).isEmpty()).isPresent();
        }

        @Override
        public void equip(Body body, ItemStack stack) {
            handler(body).orElseThrow().setStackInSlot(index, stack);
        }
    }
}
