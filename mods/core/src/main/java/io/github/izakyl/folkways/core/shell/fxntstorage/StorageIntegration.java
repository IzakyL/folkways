package io.github.izakyl.folkways.core.shell.fxntstorage;

import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.shell.storage.CellPort;
import io.github.izakyl.folkways.core.shell.storage.NativeCells;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import io.github.izakyl.folkways.core.shell.storage.Verdicts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import net.fxnt.fxntstorage.backpack.BackpackEntity;
import net.fxnt.fxntstorage.backpack.inventory.BackpackSlotLayout;
import net.fxnt.fxntstorage.backpack.upgrade.UpgradeType;
import net.fxnt.fxntstorage.container.StorageBox;
import net.fxnt.fxntstorage.container.StorageBoxEntity;
import net.fxnt.fxntstorage.controller.StorageControllerEntity;
import net.fxnt.fxntstorage.controller.StorageInterfaceEntity;
import net.fxnt.fxntstorage.reserve_storage.ReserveStorageBox;
import net.fxnt.fxntstorage.reserve_storage.ReserveStorageBoxEntity;
import net.fxnt.fxntstorage.simple_storage.SimpleStorageBoxEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class StorageIntegration {

    private StorageIntegration() {
    }

    public static void install() {
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "create_storage"),
            StorePorts.SPECIFIC, StorageIntegration::at);
    }

    private static Optional<StorePort> at(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        WorldPos home = WorldPos.of(level, pos);
        if (entity instanceof StorageBoxEntity box) {
            var cells = IntStream.range(0, box.getItemHandler().getSlots())
                .mapToObj(slot -> NativeCells.handler(box.getItemHandler(), slot, 1)).toList();
            return Optional.of(new CellPort(PortIdentity.block(home), cells, () -> {
                if (box.getBlockState().getValue(StorageBox.VOID_UPGRADE)) {
                    return Verdicts.because("void");
                }
                return box.getFilter().getFilter().isEmpty() ? Usability.USABLE : Verdicts.because("filtered");
            }, true));
        }
        if (entity instanceof SimpleStorageBoxEntity box) {
            CellPort.Cell cell = new CellPort.Cell() {
                @Override
                public ItemStack prototype() {
                    ItemStack stored = box.getItemHandler().getStackInSlot(0);
                    return stored.isEmpty() ? box.getFilterItem().copyWithCount(1) : stored.copyWithCount(1);
                }

                @Override
                public int count() {
                    return box.getItemHandler().getStackInSlot(0).getCount();
                }

                @Override
                public int limit(ItemStack stack) {
                    return box.getMaxItemCapacity();
                }

                @Override
                public boolean accepts(ItemStack stack) {
                    return box.filterTest(stack) && box.getItemHandler().isItemValid(0, stack);
                }

                @Override
                public boolean locked() {
                    return !box.getFilterItem().isEmpty();
                }

                @Override
                public void write(ItemStack prototype, int count) {
                    box.getItemHandler().setStackInSlot(0, prototype.copyWithCount(count));
                }
            };
            return Optional.of(new CellPort(PortIdentity.block(home), List.of(cell), () -> {
                if (box.hasVoidUpgrade()) {
                    return Verdicts.because("void");
                }
                return box.hasCompactingUpgrade() ? Verdicts.because("compacting") : Usability.USABLE;
            }, false));
        }
        if (entity instanceof ReserveStorageBoxEntity box) {
            var cells = IntStream.range(0, ReserveStorageBoxEntity.getGhostSlotOffset())
                .mapToObj(slot -> NativeCells.handler(box.getItemHandler(), slot, 1)).toList();
            return Optional.of(new CellPort(PortIdentity.block(home), cells, () ->
                box.getBlockState().getValue(ReserveStorageBox.VOID_UPGRADE)
                    ? Verdicts.because("void") : Usability.USABLE, true));
        }
        if (entity instanceof BackpackEntity backpack) {
            var layout = BackpackSlotLayout.createLayout();
            var cells = IntStream.range(layout.items().getStartIndex(), layout.items().getEndIndex())
                .mapToObj(slot -> NativeCells.handler(backpack.getItemHandler(), slot, backpack.getStackMultiplier()))
                .toList();
            return Optional.of(new CellPort(PortIdentity.block(home), cells, () -> {
                for (int slot = layout.upgrades().getStartIndex(); slot < layout.upgrades().getEndIndex(); slot++) {
                    ItemStack upgrade = backpack.getItemHandler().getStackInSlot(slot);
                    // Passive player effects and the manual crafting panel leave main storage intact.
                    UpgradeType type = UpgradeType.fromItem(upgrade.getItem());
                    if (!upgrade.isEmpty() && type != UpgradeType.CRAFTING && type != UpgradeType.JUKEBOX
                        && type != UpgradeType.FALLDAMAGE && type != UpgradeType.HEALTH && type != UpgradeType.FLIGHT) {
                        return Verdicts.upgrade(upgrade);
                    }
                }
                return Usability.USABLE;
            }, true));
        }
        if (entity instanceof StorageControllerEntity controller) {
            var fill = controller.getBehaviour(
                com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour.TYPE);
            if (fill != null && fill.getValue() != 0) {
                return Optional.of(NativeCells.refused(home, "filtered"));
            }
            var network = controller.getConnectedNetwork();
            List<WorldPos> members = network == null ? List.of() : network.getBoxes().stream()
                .map(box -> WorldPos.of(level, box.blockPos)).toList();
            // The native controller accepts a player's items, but has no hand extraction interaction.
            return Optional.of(new ProxyPort(home, members, network != null && !members.isEmpty(),
                Set.of(StorePort.Operation.PUT)));
        }
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        if (entity instanceof StorageInterfaceEntity || id.equals("fxntstorage:storage_interface_filtered")
            || id.equals("fxntstorage:passer_block") || id.equals("fxntstorage:smart_passer_block")) {
            return Optional.of(NativeCells.refused(home, "buffer"));
        }
        return Optional.empty();
    }
}
