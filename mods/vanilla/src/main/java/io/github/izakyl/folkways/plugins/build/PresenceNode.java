package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;

/**
 * A resident standing on a site that raises itself: what it is here for is being here, carrying its share of the
 * material, and looking busy, not the laying of any particular block.
 *
 * <p>The stay asks the colony for the materials its run of the plan spends and carries them in its pack. Each block
 * is then handed to the resident as a shot of its own ({@link ShotNode}), and that shot draws what it costs from the
 * packs of the hands on the site, its own first. A stay that has built its run takes another while the site still
 * holds anything to build with; one that can pay for nothing goes back for another basket.
 */
final class PresenceNode implements Node, Workload.Within {

    private static final int SWING_TICKS = 10;

    /** Long enough with nothing this hand can pay for: it is better sent back for a basket that fits what is left. */
    private static final int BORED_TICKS = 200;

    private final EfficientBuilder site;
    private final BlockPos stance;
    private final NodeSpec spec;
    private List<Integer> slice;
    private boolean arrived;
    private boolean drained;
    private Worker who;
    private int laid;
    private long arrivedAt = Long.MIN_VALUE;
    private long waitingSince = Long.MIN_VALUE;
    private long swungAt = Long.MIN_VALUE;

    PresenceNode(EfficientBuilder site, BlockPos stance, EfficientBuilder.Run run) {
        this.site = site;
        this.stance = stance;
        this.slice = List.copyOf(run.ids());
        this.spec = site.staySpec(this, stance, run);
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return true;
    }

    @Override
    public Workload.Within.Turn next(ServerLevel level, Worker who) {
        if (!arrived) {
            arrived = true;
            arrivedAt = level.getGameTime();
            this.who = who;
            site.touched();
            site.handArrived(this, who);
            // What the site has no use for is put away before the trip is carried, so the pack holds the trip.
            site.stow(this, false);
        }
        // The shot this hand was given came back for want of goods nothing on the site could pay for: it is better
        // sent back for another basket than kept standing where it can do nothing.
        if (drained) {
            drained = false;
            site.rest(100);
            return Workload.Within.LEAVE;
        }
        return site.hand(level, this);
    }

    /**
     * Whether the hands on the site hold, between them, what a piece costs. A piece nothing on the site carries is
     * not handed to this hand: the shot would come back for want of goods and the site would be no further on.
     */
    boolean canPay(List<Need> cost) {
        return site.poolPays(cost);
    }

    /** This hand's pack, which is part of the site's pool; null before it has been met. */
    @Nullable
    Container pack() {
        return who == null ? null : who.pack();
    }

    /** The resident itself, or null before it has been met: what the site needs to put its goods away. */
    @Nullable
    Worker handWorker() {
        return who;
    }

    /** Its run is built and the site still holds material: the hand takes the next run rather than leaving. */
    void reslice(List<Integer> next) {
        this.slice = List.copyOf(next);
        waitingSince = Long.MIN_VALUE;
    }

    /** A shot was handed over: the hand is not idle. */
    void paid() {
        waitingSince = Long.MIN_VALUE;
    }

    /** Nothing up could be paid for with what the site holds. */
    void waited(ServerLevel level) {
        if (waitingSince == Long.MIN_VALUE) {
            waitingSince = level.getGameTime();
        }
    }

    /** Long enough with nothing it can build: it is better sent back for a basket that fits what is left. */
    boolean bored(ServerLevel level) {
        return waitingSince != Long.MIN_VALUE && level.getGameTime() - waitingSince > BORED_TICKS;
    }

    /** The shot this hand was given came back for want of goods: what the site holds does not cover its run. */
    void drain() {
        drained = true;
    }

    // The resident keeps at it while it stands there: the work itself is the site's.
    @Override
    public void tick(ServerLevel level, Worker who) {
        long now = level.getGameTime();
        if (now - swungAt < SWING_TICKS) {
            return;
        }
        swungAt = now;
        who.body().swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return Outcome.done();
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return Recourse.waitFor(100);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        site.handLeft(this, how);
    }

    // Every shot handed to this stay comes back here, taken or dropped.
    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (piece instanceof ShotNode shot) {
            site.dropped(shot, how);
        }
    }

    boolean arrived() {
        return arrived;
    }

    /** Counts what this hand put in the world, so a stay can be read back as its own work. */
    void laid(int cells) {
        laid += cells;
    }

    int laid() {
        return laid;
    }

    /** How long this hand has stood on the site, or -1 if it never got there. */
    long onSiteFor(ServerLevel level) {
        return arrivedAt == Long.MIN_VALUE ? -1L : level.getGameTime() - arrivedAt;
    }

    BlockPos stance() {
        return stance;
    }

    /** This hand's own run of the plan, in the order it is built. */
    List<Integer> slice() {
        return slice;
    }
}
