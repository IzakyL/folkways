package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

final class FeedNode extends PenNode {
    static final double PARTNER_RANGE_SQR = 64.0D;

    private final UUID mate;
    private final Pen pen;

    FeedNode(Chore chore, ItemSpec feed, Pen pen) {
        super(PenNode.declare(chore, Herd.Job.FEED).needs(new Need(feed, 2)).doing(PastureContent.FEEDING).done(), chore);
        this.mate = chore.beast().mate().orElseThrow();
        this.pen = pen;
    }

    static boolean willing(Animal beast) {
        return beast.isAlive() && !beast.isRemoved() && beast.getAge() == 0 && beast.canFallInLove() && !beast.isInLove();
    }

    @Override
    public boolean ready(ServerLevel level) {
        return pair(level) != null;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Pair pair = pair(level);
        if (pair == null || !Reach.standingIn(who.body(), spec().stances())
                || !inReach(level, who, pair.first()) || !inReach(level, who, pair.second())) {
            return refuse(PastureRefusal.NOT_FEEDABLE);
        }
        List<ItemStack> food = who.supplied();
        if (food.stream().mapToInt(ItemStack::getCount).sum() != 2
                || !food.stream().allMatch(stack -> pair.first().isFood(stack) && pair.second().isFood(stack))) {
            return refuse(PastureRefusal.NOTHING_TO_WORK_WITH);
        }
        // One commit feeds both: a separate trip for the partner can outlast the first animal's love timer.
        BreedingPerks.fed(level, who, pair.first(), pair.second(), pen);
        BreedingPerks.fed(level, who, pair.second(), pair.first(), pen);
        pair.first().setInLove(null);
        pair.second().setInLove(null);
        return counted(level, new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PastureContent.trade(), 2))));
    }

    private static boolean inReach(ServerLevel level, Worker who, Animal beast) {
        return who.body().distanceToSqr(beast) <= Reach.PLAYER_BLOCK_REACH * Reach.PLAYER_BLOCK_REACH
            && (Reach.inSight(level, WorldSpaces.at(who.body()).block(level), WorldSpaces.at(beast).block(level))
                || Pen.seenOver(level, who.body().getEyePosition(), beast));
    }

    private record Pair(Animal first, Animal second) {
    }

    @Nullable
    private Pair pair(ServerLevel level) {
        if (!(level.getEntity(subject()) instanceof Animal first) || !(level.getEntity(mate) instanceof Animal second)
                || !willing(first) || !willing(second) || first.getType() != second.getType()
                || !pen.holds(first) || !pen.holds(second) || first.distanceToSqr(second) > PARTNER_RANGE_SQR) {
            return null;
        }
        return new Pair(first, second);
    }
}
