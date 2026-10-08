package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

final class EatAction implements Node {

    private static final int CHEW_TICKS = 32;

    private final LivingPresence works;
    private final NodeSpec spec;

    EatAction(ServerLevel level, LivingPresence works, UUID eater, BlockPos at) {
        this.works = works;
        this.spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID,
                WorkSite.on(level, eater, at), Stances.WHEREVER, Workload.Once.of(CHEW_TICKS))
            .effort(WorkEffort.NONE)
            .doing(LivingContent.EATING)
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
            FoodProperties food = stack.isEmpty() ? null : stack.get(DataComponents.FOOD);
            if (food == null || !works.wouldEat(who.resident().id(), food)) {
                continue;
            }
            stack.shrink(1);
            pack.setChanged();
            works.ate(who.resident().id(), food);
            return Outcome.done();
        }
        return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
    }
}
