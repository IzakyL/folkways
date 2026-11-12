package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeItem;

/** Native inventories and checked upgrade transactions on effective carried backpacks. */
final class BackpackKits implements KitProvider {
    private static final Set<String> BACKPACKS = Set.of("backpack", "leather_backpack", "copper_backpack",
        "iron_backpack", "gold_backpack", "diamond_backpack", "netherite_backpack");
    private static final Set<String> UPGRADES = Set.of("crafting_upgrade", "stonecutter_upgrade", "smithing_upgrade",
        "magnet_upgrade", "advanced_magnet_upgrade", "pickup_upgrade", "advanced_pickup_upgrade",
        "feeding_upgrade", "advanced_feeding_upgrade", "stack_upgrade_starter_tier", "stack_upgrade_tier_1",
        "stack_upgrade_tier_2", "stack_upgrade_tier_3", "stack_upgrade_tier_4", "jukebox_upgrade",
        "advanced_jukebox_upgrade");

    @Override
    public List<ItemStack> roots(Body body, List<ItemStack> worn) {
        return ResidentBackpacks.roots(body, Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.get());
    }

    @Override
    public boolean ticking(Body body, ItemStack root) {
        return ResidentBackpacks.ticking(body, root);
    }

    @Override
    public boolean accepts(ResourceLocation item) {
        return disc(item) || item.getNamespace().equals("sophisticatedbackpacks")
            && (BACKPACKS.contains(item.getPath()) || UPGRADES.contains(item.getPath()));
    }

    static boolean disc(ResourceLocation item) {
        return BuiltInRegistries.ITEM.get(item).getDefaultInstance().has(DataComponents.JUKEBOX_PLAYABLE);
    }

    static boolean backpack(ResourceLocation item) {
        return item.getNamespace().equals("sophisticatedbackpacks") && BACKPACKS.contains(item.getPath());
    }

    static boolean jukebox(ResourceLocation item) {
        return item.getNamespace().equals("sophisticatedbackpacks")
            && (item.getPath().equals("jukebox_upgrade") || item.getPath().equals("advanced_jukebox_upgrade"));
    }

    @Override
    public boolean parent(ResourceLocation item) {
        return backpack(item) || jukebox(item);
    }

    @Override
    public boolean acceptsChild(ResourceLocation parent, ResourceLocation child) {
        return backpack(parent) ? child.getNamespace().equals("sophisticatedbackpacks")
            && UPGRADES.contains(child.getPath()) : jukebox(parent) && disc(child);
    }

    @Override
    public int childSlots(ResourceLocation parent) {
        if (backpack(parent)) {
            return tier(parent.getPath()).upgradeSlotCount.get();
        }
        return jukebox(parent) && BuiltInRegistries.ITEM.get(parent) instanceof JukeboxUpgradeItem jukebox
            ? jukebox.getNumberOfSlots() : 0;
    }

    @Override
    public Optional<Component> childSlotsName(ResourceLocation parent) {
        return backpack(parent) ? Optional.of(Component.translatable("folkways.kits.sophisticated.upgrade_slots"))
            : jukebox(parent) ? Optional.of(Component.translatable("folkways.kits.sophisticated.discs"))
            : Optional.empty();
    }

    @Override
    public boolean requiresParent(ResourceLocation item) {
        return item.getNamespace().equals("sophisticatedbackpacks") && UPGRADES.contains(item.getPath());
    }

    @Override
    public Optional<Component> refusal(ResourceLocation item) {
        return item.getNamespace().equals("sophisticatedbackpacks") && !accepts(item)
            ? Optional.of(Component.translatable("folkways.kits.sophisticated.refused")) : Optional.empty();
    }

    private static Config.Server.BackpackConfig tier(String name) {
        return switch (name) {
            case "backpack", "leather_backpack" -> Config.SERVER.leatherBackpack;
            case "copper_backpack" -> Config.SERVER.copperBackpack;
            case "iron_backpack" -> Config.SERVER.ironBackpack;
            case "gold_backpack" -> Config.SERVER.goldBackpack;
            case "diamond_backpack" -> Config.SERVER.diamondBackpack;
            case "netherite_backpack" -> Config.SERVER.netheriteBackpack;
            default -> throw new IllegalArgumentException("unknown backpack tier");
        };
    }

    @Override
    public KitCapabilities capabilities(List<Held> held) {
        Map<ResourceLocation, Double> abilities = new LinkedHashMap<>();
        var stations = EnumSet.noneOf(StoreWorkstations.Capability.class);
        for (Held bag : held) {
            if (!backpack(bag.item())) {
                continue;
            }
            for (Held item : bag.children()) {
                String name = item.item().getPath();
                if (!bag.ticking() && name.contains("magnet")) {
                    // Magnets are also IPickupResponseUpgrade: chest-slot pickup still runs without magnet ticks.
                    abilities.merge(KitCapabilities.PICKUP_RADIUS, 1.5, Math::max);
                    continue;
                }
                if (!bag.ticking() && name.contains("feeding")) {
                    continue;
                }
                switch (name) {
                    case "crafting_upgrade" -> stations.add(StoreWorkstations.Capability.CRAFTING);
                    case "stonecutter_upgrade" -> stations.add(StoreWorkstations.Capability.STONECUTTING);
                    case "smithing_upgrade" -> stations.add(StoreWorkstations.Capability.SMITHING);
                    case "pickup_upgrade", "advanced_pickup_upgrade" ->
                        abilities.merge(KitCapabilities.PICKUP_RADIUS, 1.5, Math::max);
                    case "magnet_upgrade" -> abilities.merge(KitCapabilities.PICKUP_RADIUS,
                        (double) Config.SERVER.magnetUpgrade.magnetRange.get(), Math::max);
                    case "advanced_magnet_upgrade" -> abilities.merge(KitCapabilities.PICKUP_RADIUS,
                        (double) Config.SERVER.advancedMagnetUpgrade.magnetRange.get(), Math::max);
                    case "feeding_upgrade", "advanced_feeding_upgrade" -> abilities.put(KitCapabilities.AUTO_FEED, 1.0);
                    default -> { }
                }
            }
        }
        return new KitCapabilities(abilities, stations);
    }

    @Override
    public boolean active(ItemStack root, List<Integer> path) {
        if (path.isEmpty()) {
            return true;
        }
        var upgrade = wrapper(root).getUpgradeHandler().getSlotWrappers().get(path.getFirst());
        return upgrade != null && upgrade.isEnabled();
    }

    private static IBackpackWrapper wrapper(ItemStack root) {
        IBackpackWrapper wrapper = BackpackWrapper.fromStack(root);
        // Opening the real inventory initializes native storage data for a newly supplied backpack.
        wrapper.getInventoryHandler();
        return wrapper;
    }

    @Override
    public Optional<Inventory> inventory(ItemStack root) {
        if (!backpack(BuiltInRegistries.ITEM.getKey(root.getItem()))) {
            return Optional.empty();
        }
        InventoryHandler inventory = wrapper(root).getInventoryHandler();
        return Optional.of(new Inventory() {
            @Override
            public int limit(int slot, ItemStack stack) {
                return inventory.getStackLimit(slot, stack);
            }

            @Override
            public int getSlots() {
                return inventory.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return inventory.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return inventory.insertItem(slot, stack, simulate);
            }

            @Override
            public ItemStack extractItem(int slot, int count, boolean simulate) {
                return inventory.extractItem(slot, count, simulate);
            }

            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                inventory.setStackInSlot(slot, stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return inventory.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return inventory.isItemValid(slot, stack);
            }
        });
    }

    @Override
    public Optional<IItemHandler> children(ItemStack root, List<Integer> path) {
        if (!backpack(BuiltInRegistries.ITEM.getKey(root.getItem()))) {
            return Optional.empty();
        }
        var wrapper = wrapper(root);
        var upgrades = wrapper.getUpgradeHandler();
        if (path.size() == 1) {
            var upgrade = upgrades.getSlotWrappers().get(path.getFirst());
            return upgrade instanceof JukeboxUpgradeWrapper jukebox ? Optional.of(jukebox.getDiscInventory())
                : Optional.empty();
        }
        if (!path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IItemHandler() {
            @Override
            public int getSlots() {
                return upgrades.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return upgrades.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (!isItemValid(slot, stack)) {
                    return stack;
                }
                return upgrades.insertItem(slot, stack, simulate);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack stack = upgrades.getStackInSlot(slot);
                if (!(stack.getItem() instanceof IUpgradeItem<?> item)
                    || !item.canRemoveUpgradeFrom(wrapper, false).successful()) {
                    return ItemStack.EMPTY;
                }
                return upgrades.extractItem(slot, amount, simulate);
            }

            @Override
            public int getSlotLimit(int slot) {
                return upgrades.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack.getItem() instanceof IUpgradeItem<?> upgrade && upgrades.isItemValid(slot, stack)
                    && acceptsChild(BuiltInRegistries.ITEM.getKey(root.getItem()),
                        BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    && upgrade.canAddUpgradeTo(wrapper, stack, true, false).successful();
            }
        });
    }
}
