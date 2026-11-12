package io.github.izakyl.folkways.core.shell.storage.drawers;

import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerAttributes;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.IFractionalDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.IProtectable;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.LockAttribute;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityController;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityControllerIO;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityDrawers;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.mixin.DrawerControllerAccessor;
import io.github.izakyl.folkways.core.shell.mixin.DrawerSlotAccessor;
import io.github.izakyl.folkways.core.shell.storage.CellPort;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import io.github.izakyl.folkways.core.shell.storage.Verdicts;
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
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "drawers"),
            StorePorts.SPECIFIC, DrawersIntegration::at);
    }

    private static Optional<StorePort> at(ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        WorldPos home = WorldPos.of(level, pos);
        if (entity instanceof BlockEntityDrawers drawer) {
            IDrawerGroup group = drawer.getGroup();
            List<IDrawer> drawers = new ArrayList<>();
            List<CellPort.Cell> cells = new ArrayList<>();
            for (int slot = 0; slot < group.getDrawerCount(); slot++) {
                IDrawer cell = group.getDrawer(slot);
                drawers.add(cell);
                cells.add(cell(cell));
            }
            return Optional.of(new CellPort(PortIdentity.block(home), cells,
                () -> usability(List.of(drawer, group), drawers), false));
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

    private static Usability usability(List<IDrawerGroup> groups, List<IDrawer> drawers) {
        for (IDrawerGroup group : groups) {
            if (!group.isGroupValid() || group.hasMissingDrawers()) {
                return Verdicts.because("disconnected");
            }
            if (group instanceof IProtectable protectedGroup && protectedGroup.getOwner() != null) {
                return Verdicts.because("protected");
            }
        }
        for (IDrawer drawer : drawers) {
            if (drawer instanceof IFractionalDrawer) {
                return Verdicts.because("compacting");
            }
            IDrawerAttributes attributes = drawer.getAttributes();
            if (attributes.isVoid()) {
                return Verdicts.because("void");
            }
            if (attributes.isUnlimitedVending()) {
                return Verdicts.because("infinite");
            }
            if (attributes.isDictConvertible()) {
                return Verdicts.because("conversion");
            }
        }
        return Usability.USABLE;
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
