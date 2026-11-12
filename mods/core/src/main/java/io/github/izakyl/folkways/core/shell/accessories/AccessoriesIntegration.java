package io.github.izakyl.folkways.core.shell.accessories;

import io.github.izakyl.folkways.core.equipment.api.GearGroup;
import io.github.izakyl.folkways.core.equipment.api.GearSlot;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.wispforest.accessories.api.AccessoriesAPI;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories.api.slot.SlotType;
import io.wispforest.accessories.data.EntitySlotLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.util.thread.EffectiveSide;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Accessories owns its equipment and effects; compatibility layers are presented through this single registry. */
public final class AccessoriesIntegration {
    private static final ResourceLocation GROUP = ResourceLocation.parse("folkways_core:accessories");

    private AccessoriesIntegration() {
    }

    public static void install() {
        GearSlots.register(new GearGroup(GROUP, Component.literal("Accessories"), GearGroup.Placement.ACCESSORY, 1));
        GearSlots.dynamic(GROUP, AccessoriesIntegration::slots);
        GearSlots.drops(GROUP, body -> {
            var capability = io.wispforest.accessories.api.AccessoriesCapability.get(body.mob());
            if (capability == null) {
                return;
            }
            for (var container : capability.getContainers().values()) {
                for (var items : List.of(container.getAccessories(), container.getCosmeticAccessories())) {
                    for (int slot = 0; slot < items.getContainerSize(); slot++) {
                        ItemStack stack = items.getItem(slot);
                        if (!stack.isEmpty()) {
                            items.setItem(slot, ItemStack.EMPTY);
                            body.mob().spawnAtLocation(stack);
                        }
                    }
                }
            }
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.bus.api.EventPriority.LOWEST,
            (net.neoforged.neoforge.event.OnDatapackSyncEvent event) -> {
                if (event.getPlayer() != null) {
                    return;
                }
                for (var level : event.getPlayerList().getServer().getAllLevels()) {
                    for (var entity : level.getAllEntities()) {
                        io.github.izakyl.folkways.core.resident.api.Bodies.of(entity).ifPresent(
                            AccessoriesIntegration::reload);
                    }
                }
            });
    }

    private static void reload(Body body) {
        var capability = io.wispforest.accessories.api.AccessoriesCapability.get(body.mob());
        if (capability == null) {
            return;
        }
        var types = EntitySlotLoader.getEntitySlots(body.mob());
        var holder = (io.wispforest.accessories.impl.AccessoriesHolderImpl) capability.getHolder();
        var containers = ((io.github.izakyl.folkways.core.shell.mixin.AccessoriesHolderAccessor) holder)
            .folkways$containers();
        types.forEach((name, type) -> containers.computeIfAbsent(name,
            ignored -> new io.wispforest.accessories.impl.AccessoriesContainerImpl(capability, type)));
        for (var entry : containers.entrySet()) {
            var container = entry.getValue();
            if (types.containsKey(entry.getKey())) {
                container.markChanged();
                container.update();
            }
            for (var items : List.of(container.getAccessories(), container.getCosmeticAccessories())) {
                for (int index = 0; index < items.getContainerSize(); index++) {
                    ItemStack stack = items.getItem(index);
                    if (!stack.isEmpty() && (!types.containsKey(entry.getKey())
                        || !AccessoriesAPI.canInsertIntoSlot(stack, container.createReference(index)))) {
                        items.setItem(index, ItemStack.EMPTY);
                        ItemStack rest = body.pack().insert(stack);
                        if (!rest.isEmpty()) {
                            body.mob().spawnAtLocation(rest);
                        }
                    }
                }
            }
        }
        holder.setValidTypes(types.keySet());
        // Native resizing reports overflow here. Residents use their pack in place of a player's inventory.
        for (ItemStack stack : List.copyOf(holder.invalidStacks)) {
            ItemStack rest = body.pack().insert(stack);
            if (!rest.isEmpty()) {
                body.mob().spawnAtLocation(rest);
            }
        }
        holder.invalidStacks.clear();
        io.wispforest.accessories.networking.client.SyncEntireContainer.syncTo(body.mob(), packet ->
            io.wispforest.accessories.networking.AccessoriesNetworking.sendToTrackingAndSelf(body.mob(), packet));
    }

    private static Level level() {
        return EffectiveSide.get() == LogicalSide.CLIENT ? Client.level()
            : ServerLifecycleHooks.getCurrentServer() == null ? null : ServerLifecycleHooks.getCurrentServer()
                .overworld();
    }

    private static final class Client {
        static Level level() {
            return net.minecraft.client.Minecraft.getInstance().level;
        }
    }

    private static List<GearSlot> slots() {
        Level level = level();
        if (level == null) {
            return List.of();
        }
        List<GearSlot> result = new ArrayList<>();
        EntitySlotLoader.getEntitySlots(level, EntityType.PLAYER).values().stream().sorted().forEach(type -> {
            for (int index = 0; index < type.amount(); index++) {
                result.add(new Slot(type, index));
            }
        });
        return List.copyOf(result);
    }

    private record Slot(SlotType type, int index) implements GearSlot {
        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("accessories", type.name().replace("/", "//").replace(':', '/') + "/" + index);
        }

        @Override
        public ResourceLocation group() {
            return GROUP;
        }

        @Override
        public int order() {
            return type.order() * 1000 + index;
        }

        @Override
        public Component name() {
            Component name = Component.translatable(type.translation());
            return type.amount() > 1 ? name.copy().append(" " + (index + 1)) : name;
        }

        @Override
        public boolean atlasIcon() {
            return true;
        }

        @Override
        public Optional<ResourceLocation> emptyIcon() {
            return Optional.of(type.icon());
        }

        private SlotReference reference(Body body) {
            return SlotReference.of(body.mob(), type.name(), index);
        }

        @Override
        public boolean fits(ItemStack stack) {
            // Without a resident, only the framework's nullable-entity slot predicates can be evaluated.
            Level level = level();
            return !stack.isEmpty() && level != null
                && AccessoriesAPI.getPredicateResults(type.validators(), level, null, type, index, stack);
        }

        @Override
        public boolean fits(Body body, ItemStack stack) {
            var reference = reference(body);
            return !stack.isEmpty() && reference.isValid() && AccessoriesAPI.canInsertIntoSlot(stack, reference);
        }

        @Override
        public ItemStack worn(Body body) {
            var reference = reference(body);
            return reference.isValid() ? reference.getStack() : ItemStack.EMPTY;
        }

        @Override
        public boolean removable(Body body) {
            return AccessoriesAPI.canUnequip(worn(body), reference(body));
        }

        @Override
        public void equip(Body body, ItemStack stack) {
            if (!reference(body).setStack(stack)) {
                throw new IllegalStateException("accessory slot disappeared: " + id());
            }
        }
    }
}
