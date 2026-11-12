package io.github.izakyl.folkways.core.shell.mekanism;

import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
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
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "qio"),
            StorePorts.SPECIFIC, QioIntegration::at);
    }

    private static Optional<StorePort> at(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof TileEntityQIOComponent device)) {
            return Optional.empty();
        }
        WorldPos home = WorldPos.of(level, pos);
        if (!(device instanceof TileEntityQIODashboard)) {
            // Drive arrays expose drives, not their goods. Automation devices do not grant resident access.
            return Optional.of(NativeCells.refused(home,
                device instanceof TileEntityQIODriveArray ? "hand_access" : "buffer"));
        }
        EnumSet<StorePort.Operation> operations = EnumSet.noneOf(StorePort.Operation.class);
        if (device.getBlockState().is(TAKE)) {
            operations.add(StorePort.Operation.TAKE);
        }
        if (device.getBlockState().is(PUT)) {
            operations.add(StorePort.Operation.PUT);
        }
        return Optional.of(new QioPort(level, pos, device, operations));
    }
}
