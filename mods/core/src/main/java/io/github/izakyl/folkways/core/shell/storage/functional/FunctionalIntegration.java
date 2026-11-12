package io.github.izakyl.folkways.core.shell.storage.functional;

import com.buuz135.functionalstorage.block.tile.CompactingDrawerTile;
import com.buuz135.functionalstorage.block.tile.ControllableDrawerTile;
import com.buuz135.functionalstorage.block.tile.DrawerTile;
import com.buuz135.functionalstorage.block.tile.SimpleCompactingDrawerTile;
import com.buuz135.functionalstorage.block.tile.StorageControllerExtensionTile;
import com.buuz135.functionalstorage.block.tile.StorageControllerTile;
import com.buuz135.functionalstorage.inventory.BigInventoryHandler;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.storage.CellPort;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import io.github.izakyl.folkways.core.shell.storage.Verdicts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

public final class FunctionalIntegration {

    private static final Set<String> SAFE_UTILITY = Set.of("collector_upgrade", "puller_upgrade", "pusher_upgrade",
        "redstone_upgrade");
    private static final Set<String> SAFE_STORAGE = Set.of("copper_upgrade", "gold_upgrade", "diamond_upgrade",
        "netherite_upgrade", "iron_downgrade", "max_storage_upgrade");

    private FunctionalIntegration() {
    }

    public static void install() {
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "functional"),
            StorePorts.SPECIFIC, FunctionalIntegration::at);
    }

    private static Optional<StorePort> at(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ControllableDrawerTile<?> tile)) {
            return Optional.empty();
        }
        WorldPos home = WorldPos.of(level, pos);
        if (tile instanceof StorageControllerTile<?> || tile instanceof StorageControllerExtensionTile<?>) {
            StorageControllerTile<?> controller = tile instanceof StorageControllerTile<?> own ? own : null;
            BlockPos linked = tile.getControllerPos();
            if (controller == null && linked != null && level.isLoaded(linked)
                && level.getBlockEntity(linked) instanceof StorageControllerTile<?> found) {
                controller = found;
            }
            List<WorldPos> members = new ArrayList<>();
            if (controller != null) {
                for (long address : controller.getConnectedDrawers().getConnectedDrawers()) {
                    BlockPos member = BlockPos.of(address);
                    // Extensions are access points, not stores represented by another access point.
                    if (!level.isLoaded(member)
                        || !(level.getBlockEntity(member) instanceof StorageControllerExtensionTile<?>)) {
                        members.add(WorldPos.of(level, member));
                    }
                }
            }
            return Optional.of(new ProxyPort(home, members, controller != null));
        }
        List<CellPort.Cell> cells = new ArrayList<>();
        if (tile instanceof DrawerTile drawer) {
            BigInventoryHandler inventory = drawer.getHandler();
            for (int slot = 0; slot < inventory.getStoredStacks().size(); slot++) {
                cells.add(cell(inventory, slot));
            }
        }
        return Optional.of(new CellPort(PortIdentity.block(home), cells, () -> usability(List.of(tile)), false));
    }

    private static Usability usability(List<ControllableDrawerTile<?>> members) {
        for (ControllableDrawerTile<?> member : members) {
            if (member instanceof CompactingDrawerTile || member instanceof SimpleCompactingDrawerTile) {
                return Verdicts.because("compacting");
            }
            if (!(member instanceof DrawerTile || member instanceof StorageControllerTile<?>
                || member instanceof StorageControllerExtensionTile<?>)) {
                return Verdicts.because("unsupported");
            }
            for (int kind = 0; kind < 2; kind++) {
                IItemHandler upgrades = kind == 0 ? member.getUtilityUpgrades() : member.getStorageUpgrades();
                if (upgrades == null) {
                    continue;
                }
                Set<String> safe = kind == 0 ? SAFE_UTILITY : SAFE_STORAGE;
                for (int slot = 0; slot < upgrades.getSlots(); slot++) {
                    ItemStack upgrade = upgrades.getStackInSlot(slot);
                    if (upgrade.isEmpty()) {
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(upgrade.getItem());
                    if (!id.getNamespace().equals("functionalstorage") || !safe.contains(id.getPath())) {
                        return Verdicts.upgrade(upgrade);
                    }
                }
            }
        }
        return Usability.USABLE;
    }

    private static CellPort.Cell cell(BigInventoryHandler inventory, int slot) {
        return new CellPort.Cell() {
            @Override
            public ItemStack prototype() {
                return inventory.getStoredStacks().get(slot).getStack().copy();
            }

            @Override
            public int count() {
                return inventory.getStoredStacks().get(slot).getAmount();
            }

            @Override
            public int limit(ItemStack stack) {
                return inventory.getSlotLimit(slot, stack);
            }

            @Override
            public boolean accepts(ItemStack stack) {
                return inventory.isItemValid(slot, stack);
            }

            @Override
            public boolean locked() {
                return inventory.isLocked();
            }

            @Override
            public void write(ItemStack prototype, int count) {
                BigInventoryHandler.BigStack state = inventory.getStoredStacks().get(slot);
                state.setStack(prototype);
                state.setAmount(count);
                inventory.onChange();
            }
        };
    }
}
