package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.common.storage.externalstorage.AbstractExternalStorageBlockEntity;
import com.refinedmods.refinedstorage.common.storage.externalstorage.ExternalStorageBlock;
import io.github.izakyl.folkways.core.facility.api.ExternalAttachments;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ExternalStorage implements ExternalAttachments.Adapter {

    @Override
    public boolean host(BlockEntity entity) {
        return entity instanceof AbstractNetworkNodeContainerBlockEntity<?>;
    }

    @Override
    public void targets(BlockEntity entity, Consumer<ExternalAttachments.Target> target) {
        if (!(entity instanceof AbstractExternalStorageBlockEntity external)) {
            return;
        }
        var network = external.getContainerProvider().getContainers().stream().findFirst()
            .map(container -> (com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode)
                container.getNode()).map(node -> node.getNetwork()).orElse(null);
        Direction side = facing(entity.getBlockState());
        if (side != null) {
            // Native RS2 interfaces expose an export buffer, even to RS2 External Storage.
            target.accept(new ExternalAttachments.Target(entity.getBlockPos().relative(side), side.getOpposite(), network));
        }
    }

    static Direction facing(BlockState state) {
        return state.getBlock() instanceof ExternalStorageBlock external ? external.getDirection(state) : null;
    }

    @Override
    public boolean access(ServerLevel level, BlockPos pos, Consumer<Object> network) {
        var port = Rs2Devices.at(level, pos).filter(device -> !device.operations().isEmpty() || device.crafts());
        port.ifPresent(value -> {
            var device = value;
            network.accept(device.node() == null ? null : device.node().getNetwork());
        });
        return port.isPresent();
    }
}
