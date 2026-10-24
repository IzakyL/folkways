package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;

final class NurtureNode extends AnimalNode {
    private final java.util.UUID assigned;
    @Override public Optional<java.util.UUID> worker() { return Optional.of(assigned); }
    NurtureNode(Chore chore, ItemSpec food, java.util.UUID assigned) {
        super(PenNode.declare(chore, Herd.Job.NURTURE).needs(new Need(food, 1))
            .doing(PastureContent.FEEDING).done(), chore);
        this.assigned = assigned;
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Animal beast) {
        double bonus = who.effects().value(PastureContent.BABY_GROWTH);
        if (!beast.isBaby() || beast.getPersistentData().getBoolean(BreedingPerks.NURTURED)
                || bonus <= 0 || who.supplied().stream().mapToInt(stack -> stack.isEmpty()
                    || !beast.isFood(stack) ? 0 : stack.getCount()).sum() != 1) {
            return refuse(PastureRefusal.NOT_FEEDABLE);
        }
        // Normal feeding advances ten percent; the specialist additionally removes its stated share.
        int remaining = -beast.getAge();
        beast.setAge(-Math.max(0, (int) Math.round(remaining * .9 * (1 - bonus))));
        beast.getPersistentData().putBoolean(BreedingPerks.NURTURED, true);
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PastureContent.trade(), 1)));
    }
}
