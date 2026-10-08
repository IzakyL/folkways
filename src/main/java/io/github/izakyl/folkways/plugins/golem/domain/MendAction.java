package io.github.izakyl.folkways.plugins.golem.domain;

import dev.xkmc.modulargolems.content.config.GolemMaterial;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

final class MendAction implements Node {

    private static final int MEND_TICKS = 40;

    private final NodeSpec spec;

    MendAction(ServerLevel level, UUID golem, BlockPos at) {
        this.spec = NodeSpec.of(UUID.randomUUID(), GolemContent.ID,
                WorkSite.on(level, golem, at), Stances.WHEREVER, Workload.Once.of(MEND_TICKS))
            .gesture(WorkGesture.SWING)
            .doing(GolemContent.MENDING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Container pack = who.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.isEmpty() || GolemMaterial.getRepairMaterial(stack).isEmpty()) {
                continue;
            }
            if (!(who.body() instanceof AbstractGolemEntity<?, ?> body)) {
                break;
            }
            stack.shrink(1);
            pack.setChanged();
            body.repairWithItem();
            return Outcome.done();
        }
        return Outcome.failed(GolemRefusal.NOTHING_TO_MEND_WITH);
    }
}
