package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.IGridServiceProvider;
import appeng.parts.AEBasePart;
import io.github.izakyl.folkways.core.facility.api.ExternalAttachments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Invalidate attachment and access hosts when their nodes join or leave a grid. */
public final class AttachmentGridService implements IGridService, IGridServiceProvider {

    public AttachmentGridService(IGrid grid) {
    }

    @Override
    public void addNode(IGridNode node, CompoundTag data) {
        changed(node);
    }

    @Override
    public void removeNode(IGridNode node) {
        changed(node);
    }

    private static void changed(IGridNode node) {
        var entity = switch (node.getOwner()) {
            case BlockEntity block -> block;
            case AEBasePart part -> part.getBlockEntity();
            case null, default -> null;
        };
        if (entity != null) {
            ExternalAttachments.blockChanged(node.getLevel(), entity.getBlockPos());
        }
    }
}
