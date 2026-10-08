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

final class FeedNode extends AnimalNode {

    FeedNode(Chore chore, ItemSpec feed) {
        super(PenNode.declare(chore, Herd.Job.FEED).needs(new Need(feed, 1)).doing(PastureContent.FEEDING).done(), chore);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Animal beast) {
        if (beast.isBaby() || !beast.canFallInLove() || beast.isInLove()) {
            return refuse(PastureRefusal.NOT_FEEDABLE);
        }
        if (!who.supplied().stream().allMatch(beast::isFood)) {
            return refuse(PastureRefusal.NOTHING_TO_WORK_WITH);
        }
        beast.setInLove(null);
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PastureContent.trade(), 1)));
    }
}
