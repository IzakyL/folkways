package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.storage.CellPort;
import io.github.izakyl.folkways.core.shell.storage.Verdicts;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IIOFilterUpgrade;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;

final class SophisticatedPort {

    // Audited against the pinned jars. Unknown upgrades fail closed, including third-party processing upgrades.
    private static final Set<String> SAFE = Set.of(
        "pickup_upgrade", "advanced_pickup_upgrade", "magnet_upgrade", "advanced_magnet_upgrade",
        "filter_upgrade", "advanced_filter_upgrade", "hopper_upgrade", "advanced_hopper_upgrade",
        "restock_upgrade", "advanced_restock_upgrade", "deposit_upgrade", "advanced_deposit_upgrade",
        "refill_upgrade", "advanced_refill_upgrade", "tool_swapper_upgrade", "advanced_tool_swapper_upgrade",
        "inception_upgrade", "everlasting_upgrade", "jukebox_upgrade", "advanced_jukebox_upgrade",
        "pump_upgrade", "advanced_pump_upgrade",
        "crafting_upgrade", "stonecutter_upgrade", "anvil_upgrade", "smithing_upgrade",
        "stack_upgrade_starter_tier", "stack_upgrade_tier_1", "stack_upgrade_tier_1_plus", "stack_upgrade_tier_2",
        "stack_upgrade_tier_3", "stack_upgrade_tier_4", "stack_upgrade_tier_5", "stack_upgrade_omega_tier",
        "stack_downgrade_tier_1", "stack_downgrade_tier_2", "stack_downgrade_tier_3");

    private record Contents(UUID id) {
    }

    private SophisticatedPort() {
    }

    static StorePort create(ServerLevel level, BlockPos pos, IStorageWrapper wrapper, BooleanSupplier locked) {
        InventoryHandler inventory = wrapper.getInventoryHandler();
        MemorySettingsCategory memory = wrapper.getSettingsHandler().getTypeCategory(MemorySettingsCategory.class);
        List<CellPort.Cell> cells = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            int index = slot;
            cells.add(new CellPort.Cell() {
                @Override
                public ItemStack prototype() {
                    ItemStack stack = inventory.getStackInSlot(index);
                    return stack.isEmpty() ? memory.getSlotFilterStack(index, true).orElse(ItemStack.EMPTY)
                        : stack.copy();
                }

                @Override
                public int count() {
                    return inventory.getStackInSlot(index).getCount();
                }

                @Override
                public int limit(ItemStack stack) {
                    return inventory.getStackLimit(index, stack);
                }

                @Override
                public boolean accepts(ItemStack stack) {
                    return inventory.isItemValid(index, stack) && wrapper.getUpgradeHandler()
                        .getWrappersThatImplement(IIOFilterUpgrade.class).stream()
                        .allMatch(filter -> filter.getInputFilter()
                            .map(logic -> logic.matchesFilter(stack)).orElse(true));
                }

                @Override
                public boolean extracts(ItemStack stack) {
                    return wrapper.getUpgradeHandler().getWrappersThatImplement(IIOFilterUpgrade.class).stream()
                        .allMatch(filter -> filter.getOutputFilter()
                            .map(logic -> logic.matchesFilter(stack)).orElse(true));
                }

                @Override
                public boolean locked() {
                    return locked.getAsBoolean() || memory.isSlotSelected(index);
                }

                @Override
                public boolean accessible() {
                    return inventory.isSlotAccessible(index);
                }

                @Override
                public void write(ItemStack prototype, int count) {
                    inventory.setStackInSlot(index, prototype.copyWithCount(count));
                }
            });
        }
        WorldPos home = WorldPos.of(level, pos);
        PortIdentity identity = wrapper.getContentsUuid().map(id -> new PortIdentity(new Contents(id), home))
            .orElseGet(() -> PortIdentity.block(home));
        return new CellPort(identity, cells, () -> usability(wrapper), true, () -> upgradeRevision(wrapper));
    }

    private static long upgradeRevision(IStorageWrapper wrapper) {
        UpgradeHandler upgrades = wrapper.getUpgradeHandler();
        long signature = 0;
        for (int slot = 0; slot < upgrades.getSlots(); slot++) {
            signature = 31L * signature + ItemStack.hashItemAndComponents(upgrades.getStackInSlot(slot));
        }
        return signature;
    }

    static Usability usability(IStorageWrapper wrapper) {
        UpgradeHandler upgrades = wrapper.getUpgradeHandler();
        for (int slot = 0; slot < upgrades.getSlots(); slot++) {
            ItemStack upgrade = upgrades.getStackInSlot(slot);
            if (upgrade.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(upgrade.getItem());
            if (!(id.getNamespace().equals("sophisticatedstorage")
                || id.getNamespace().equals("sophisticatedbackpacks"))
                || !SAFE.contains(id.getPath())) {
                return Verdicts.upgrade(upgrade);
            }
        }
        // Inception may expose processing upgrades from contained backpacks to the main inventory.
        for (IUpgradeWrapper effective : upgrades.getWrappersThatImplement(IUpgradeWrapper.class)) {
            ItemStack upgrade = effective.getUpgradeStack();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(upgrade.getItem());
            if (!(id.getNamespace().equals("sophisticatedstorage")
                || id.getNamespace().equals("sophisticatedbackpacks"))
                || !SAFE.contains(id.getPath())) {
                return Verdicts.upgrade(upgrade);
            }
        }
        return Usability.USABLE;
    }
}
