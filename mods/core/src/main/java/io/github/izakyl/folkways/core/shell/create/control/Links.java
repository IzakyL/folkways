package io.github.izakyl.folkways.core.shell.create.control;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class Links {
    private Links() {
    }

    public static boolean eligible(BlockEntity entity) {
        if (!(entity instanceof RedstoneLinkBlockEntity link)) {
            return false;
        }
        LinkBehaviour behaviour = link.getBehaviour(LinkBehaviour.TYPE);
        return entity.getBlockState().getBlock() instanceof RedstoneLinkBlock
            && entity.getBlockState().getValue(RedstoneLinkBlock.RECEIVER)
            && behaviour != null && behaviour.isListening()
            && behaviour.getNetworkKey().both(frequency -> frequency.getStack().isEmpty());
    }
}
