package io.github.izakyl.folkways.core.shell.mekanism;

import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.shell.storage.NativeCells;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.EnumSet;
import java.util.Optional;
import mekanism.common.tile.qio.TileEntityQIOComponent;
import mekanism.common.tile.qio.TileEntityQIODashboard;
import mekanism.common.tile.qio.TileEntityQIODriveArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class QioIntegration {

    private static final TagKey<Block> TAKE = tag("take");
    private static final TagKey<Block> PUT = tag("put");

    private QioIntegration() {
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "network_access/" + name));
    }

    public static void install() {
        Ports.registerItem(ResourceLocation.fromNamespaceAndPath("folkways_core", "qio"),
            Ports.SPECIFIC, QioIntegration::at);
    }

    private static Optional<ItemPort> at(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof TileEntityQIOComponent component)) {
            return Optional.empty();
        }
        WorldPos home = WorldPos.of(level, pos);
        if (!(component instanceof TileEntityQIODashboard)) {
            // Drive arrays expose drives, not their goods. Automation devices do not grant resident access.
            return Optional.of(NativeCells.refused(home,
                component instanceof TileEntityQIODriveArray ? "hand_access" : "buffer"));
        }
        EnumSet<ItemPort.Operation> operations = EnumSet.noneOf(ItemPort.Operation.class);
        if (component.getBlockState().is(TAKE)) {
            operations.add(ItemPort.Operation.TAKE);
        }
        if (component.getBlockState().is(PUT)) {
            operations.add(ItemPort.Operation.PUT);
        }
        return Optional.of(new QioPort(level, pos, component, operations));
    }
}
