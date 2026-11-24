package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.parts.IPartHost;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.parts.misc.InterfacePart;
import appeng.parts.storagebus.StorageBusPart;
import io.github.izakyl.folkways.core.facility.api.ExternalAttachments;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

final class ExternalStorage implements ExternalAttachments.Adapter {

    @Override
    public boolean host(BlockEntity entity) {
        return entity instanceof IPartHost || entity instanceof appeng.api.networking.IInWorldGridNodeHost;
    }

    @Override
    public void targets(BlockEntity entity, Consumer<ExternalAttachments.Target> target) {
        if (!(entity instanceof IPartHost host)) {
            return;
        }
        for (Direction side : Direction.values()) {
            if (host.getPart(side) instanceof StorageBusPart bus) {
                var node = bus.getGridNode();
                target.accept(new ExternalAttachments.Target(entity.getBlockPos().relative(side), side.getOpposite(),
                    node == null ? null : node.getGrid()));
            }
        }
    }

    @Override
    public Object network(ServerLevel level, BlockPos pos, Direction face) {
        return networkAt(level, pos, face);
    }

    static Object networkAt(ServerLevel level, BlockPos pos, Direction face) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        var entity = level.getBlockEntity(pos);
        if (entity instanceof InterfaceBlockEntity access && access.getInterfaceLogic().getConfig().isEmpty()) {
            return access.getMainNode().getGrid();
        }
        if (entity instanceof IPartHost host && host.getPart(face) instanceof InterfacePart access
                && access.getInterfaceLogic().getConfig().isEmpty()) {
            var node = access.getGridNode();
            return node == null ? null : node.getGrid();
        }
        return null;
    }

    @Override
    public boolean access(ServerLevel level, BlockPos pos, Consumer<Object> network) {
        var port = Ae2Devices.at(level, pos).filter(device -> !device.operations().isEmpty() || device.crafts());
        port.ifPresent(value -> {
            var device = value;
            network.accept(device.node() == null ? null : device.node().getGrid());
        });
        return port.isPresent();
    }
}
