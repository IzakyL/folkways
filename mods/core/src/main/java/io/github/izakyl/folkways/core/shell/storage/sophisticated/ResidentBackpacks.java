package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.shell.mixin.BackpackInventoryProviderAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.UUIDDeduplicator;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.linkedstorage.LinkedStorageEndpointStackState;
import net.p3pp3rf1y.sophisticatedcore.linkedstorage.LinkedStorageStackLifecycle;

/** The resident equivalents of PlayerInventoryProvider and CommonEventHandler's periodic checks. */
final class ResidentBackpacks {

    private ResidentBackpacks() {
    }

    static boolean ticking(Body body, ItemStack stack) {
        if (!Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.get()) {
            return true;
        }
        // NeoForge Inventory.tick supplies positive indices even for armor. Frameworks supply -1.
        List<ItemStack> accessories = new ArrayList<>();
        var handlers = ((BackpackInventoryProviderAccessor) PlayerInventoryProvider.get()).folkways$handlers();
        if (handlers.containsKey("curios")) {
            Curios.add(body, accessories);
        }
        if (handlers.containsKey("accessories")) {
            Accessories.add(body, accessories);
        }
        return accessories.stream().anyMatch(candidate -> candidate == stack);
    }

    static List<ItemStack> roots(Body body, boolean onlyWorn) {
        List<ItemStack> stacks = new ArrayList<>();
        var handlers = ((BackpackInventoryProviderAccessor) PlayerInventoryProvider.get()).folkways$handlers();
        for (var entry : handlers.entrySet()) {
            if (onlyWorn && !entry.getValue().isAccessibleByAnotherPlayer()) {
                continue;
            }
            switch (entry.getKey()) {
                case "armor" -> stacks.add(body.mob().getItemBySlot(EquipmentSlot.CHEST));
                case "offhand" -> stacks.add(body.mob().getOffhandItem());
                case "main" -> {
                    // Read only base cells. Flattened backpack cells are not player inventory roots.
                    for (int slot = 0; slot < body.pack().ownCells(); slot++) {
                        stacks.add(body.pack().getItem(slot));
                    }
                }
                case "curios" -> Curios.add(body, stacks);
                case "accessories" -> Accessories.add(body, stacks);
                default -> { }
            }
        }
        return stacks.stream().filter(stack -> !stack.isEmpty() && stack.getItem() instanceof BackpackItem)
            .toList();
    }

    private static final class Curios {
        static void add(Body body, List<ItemStack> stacks) {
            var identifiers = new LinkedHashSet<>(top.theillusivec4.curios.api.CuriosApi
                .getItemStackSlots(ModItems.BACKPACK.get().getDefaultInstance(), false).keySet());
            identifiers.add("curio");
            top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(body.mob()).ifPresent(inventory -> {
                for (String identifier : identifiers) {
                    inventory.getStacksHandler(identifier).ifPresent(handler -> {
                        for (int slot = 0; slot < handler.getSlots(); slot++) {
                            stacks.add(handler.getStacks().getStackInSlot(slot));
                        }
                    });
                }
            });
        }
    }

    private static final class Accessories {
        static void add(Body body, List<ItemStack> stacks) {
            io.wispforest.accessories.api.AccessoriesCapability.getOptionally(body.mob()).ifPresent(capability -> {
                for (var container : capability.getContainers().values()) {
                    for (int slot = 0; slot < container.getSize(); slot++) {
                        stacks.add(container.getAccessories().getItem(slot));
                    }
                }
            });
        }
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(ResidentBackpacks::tick);
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 40 != 0) {
            return;
        }
        Map<UUID, IBackpackWrapper> ids = new HashMap<>();
        if (!Config.SERVER.tickDedupeLogicDisabled.get()) {
            // Include players so a copied UUID cannot expose the same contents through both owners.
            for (var player : level.players()) {
                PlayerInventoryProvider.get().runOnBackpacks(player, (stack, handler, identifier, slot) -> {
                    deduplicate(ids, stack);
                    return false;
                });
            }
        }
        for (var entity : level.getAllEntities()) {
            Bodies.of(entity).ifPresent(body -> check(body, ids));
        }
    }

    static void check(Body body, Map<UUID, IBackpackWrapper> ids) {
        List<ItemStack> all = roots(body, false);
        if (!Config.SERVER.tickDedupeLogicDisabled.get()) {
            all.forEach(stack -> deduplicate(ids, stack));
        }
        var nerfs = Config.SERVER.nerfsConfig;
        int extra = new java.util.HashSet<>(all).size() - nerfs.maxNumberOfBackpacks.get();
        if (nerfs.tooManyBackpacksSlowness.get() && extra > 0) {
            int levels = Math.min(10, (int) Math.ceil(extra * nerfs.slownessLevelsPerAdditionalBackpack.get()));
            body.mob().addEffect(new MobEffectInstance(nerfs.getEffect(), 80, levels - 1, false, false));
        }
    }

    private static void deduplicate(Map<UUID, IBackpackWrapper> ids, ItemStack stack) {
        if (LinkedStorageStackLifecycle.classifyEndpoint(stack) == LinkedStorageEndpointStackState.ENDPOINT) {
            return;
        }
        var wrapper = BackpackWrapper.fromStack(stack);
        wrapper.getContentsUuid().ifPresent(id -> {
            var previous = ids.get(id);
            ids.put(id, previous != null && previous.getBackpack() != stack
                ? UUIDDeduplicator.dedupeBackpackWrappers(previous, wrapper) : wrapper);
        });
    }
}
