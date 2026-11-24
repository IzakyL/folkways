package io.github.izakyl.folkways.core.shell.storage.drawers;

import io.github.izakyl.folkways.core.facility.api.Ports;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerAttributes;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.IFractionalDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.IProtectable;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.LockAttribute;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityController;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityControllerIO;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityDrawers;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.mixin.DrawerControllerAccessor;
import io.github.izakyl.folkways.core.shell.mixin.DrawerSlotAccessor;
import io.github.izakyl.folkways.core.shell.storage.CellPort;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import io.github.izakyl.folkways.core.shell.storage.StorageTraits;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class DrawersIntegration {

    private DrawersIntegration() {
    }

    public static void install() {
        Ports.registerItem(ResourceLocation.fromNamespaceAndPath("folkways_core", "drawers"),
            Ports.SPECIFIC, DrawersIntegration::at);
    }

    private static Optional<ItemPort> at(ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        WorldPos home = WorldPos.of(level, pos);
        if (entity instanceof BlockEntityDrawers drawer) {
            IDrawerGroup group = drawer.getGroup();
            List<IDrawer> drawers = new ArrayList<>();
            for (int slot = 0; slot < group.getDrawerCount(); slot++) {
                drawers.add(group.getDrawer(slot));
            }
            // A compacting drawer's forms share one counter. Transfer against its base unit once.
            List<CellPort.Cell> cells = drawers.stream()
                .filter(cell -> !(cell instanceof IFractionalDrawer form) || form.isSmallestUnit())
                .map(DrawersIntegration::cell).toList();
            return Optional.of(new CellPort(PortIdentity.block(home), cells,
                () -> traits(List.of(drawer, group), drawers), false));
        }
        BlockEntityController controller;
        if (entity instanceof BlockEntityController own) {
            controller = own;
        } else if (entity instanceof BlockEntityControllerIO io) {
            controller = io.getController();
        } else {
            return Optional.empty();
        }
        List<WorldPos> members = new ArrayList<>();
        if (controller != null) {
            // Native groups are data shims, not block entities; slot records retain their physical positions.
            for (Object slot : ((DrawerControllerAccessor) controller).folkways$drawerSlots()) {
                if (slot != null) {
                    BlockPos member = ((DrawerSlotAccessor) slot).folkways$position();
                    if (!member.equals(controller.getBlockPos())) {
                        members.add(WorldPos.of(level, member));
                    }
                }
            }
        }
        return Optional.of(new ProxyPort(home, members, controller != null && controller.isGroupValid()));
    }

    private static StorageTraits traits(List<IDrawerGroup> groups, List<IDrawer> drawers) {
        for (IDrawerGroup group : groups) {
            if (!group.isGroupValid() || group.hasMissingDrawers()) {
                return StorageTraits.unavailable("disconnected");
            }
            if (group instanceof IProtectable protectedGroup && protectedGroup.getOwner() != null) {
                return StorageTraits.unavailable("protected");
            }
        }
        for (IDrawer drawer : drawers) {
            if (drawer instanceof IFractionalDrawer) {
                List<IFractionalDrawer> forms = drawers.stream().filter(IFractionalDrawer.class::isInstance)
                    .map(IFractionalDrawer.class::cast).filter(form -> !form.getStoredItemPrototype().isEmpty())
                    .sorted(java.util.Comparator.comparingInt(IFractionalDrawer::getConversionRate)).toList();
                if (forms.size() >= 2) {
                    return StorageTraits.compacting(forms.get(0).getStoredItemPrototype(),
                        forms.get(1).getStoredItemPrototype());
                }
                return StorageTraits.compacting();
            }
            IDrawerAttributes attributes = drawer.getAttributes();
            if (attributes.isVoid()) {
                return StorageTraits.voiding(drawer.getStoredItemPrototype());
            }
            if (attributes.isUnlimitedVending()) {
                return StorageTraits.source(drawer.getStoredItemPrototype());
            }
            if (attributes.isDictConvertible()) {
                return StorageTraits.conversion();
            }
        }
        return StorageTraits.PLAIN;
    }

    private static CellPort.Cell cell(IDrawer drawer) {
        return new CellPort.Cell() {
            @Override
            public ItemStack prototype() {
                return drawer.getStoredItemPrototype().copy();
            }

            @Override
            public int count() {
                return drawer.getStoredItemCount();
            }

            @Override
            public int limit(ItemStack stack) {
                return drawer.getMaxCapacity(stack);
            }

            @Override
            public boolean accepts(ItemStack stack) {
                return drawer.canItemBeStored(stack);
            }

            @Override
            public boolean extracts(ItemStack stack) {
                return drawer.canItemBeExtracted(stack);
            }

            @Override
            public boolean locked() {
                return drawer.getStoredItemPrototype().isEmpty()
                    ? drawer.getAttributes().isItemLocked(LockAttribute.LOCK_EMPTY)
                    : drawer.getAttributes().isItemLocked(LockAttribute.LOCK_POPULATED);
            }

            @Override
            public boolean accessible() {
                return drawer.isEnabled() && !drawer.getAttributes().isSealed()
                    && !drawer.getAttributes().isSuspended();
            }

            @Override
            public void write(ItemStack prototype, int count) {
                drawer.setStoredItem(prototype, count);
            }
        };
    }
}
