package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class MilkNode extends AnimalNode {

    private final Herd herd;

    MilkNode(WorkSite at, Stances stances, UUID cow, Herd herd) {
        super(NodeSpec.of(UUID.randomUUID(), PastureContent.ID, at, stances,
                Workload.Once.of(MilkHost.LABOR_TICKS, PastureContent::haste))
            .needs(new Need(MilkHost.BUCKET, 1))
            .gives(new Amount(MilkHost.MILK, 1))
            .vocation(PastureContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(at.cell())
            .doing(PastureContent.MILKING)
            .done(), cow, herd.made());
        this.herd = herd;
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Animal beast) {
        if (beast.isBaby()) {
            return refuse(PastureRefusal.NOTHING_TO_TAKE);
        }
        herd.milked(animal(), level.getGameTime());
        return new Outcome.Done(List.of(PastureNoise.MILKED),
            List.of(new ItemStack(Items.MILK_BUCKET)),
            Optional.of(new Xp(PastureContent.trade(), 1)));
    }
}
