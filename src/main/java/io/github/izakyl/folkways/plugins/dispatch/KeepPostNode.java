package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

final class KeepPostNode implements Node, Workload.Continuous {

    private final NodeSpec spec;
    private final Vocation trade;
    private final Post post;
    private final KeeperPosts posts;
    private volatile boolean tried;
    private volatile boolean sat;

    KeepPostNode(UUID id, Vocation trade, Post post, KeeperPosts posts) {
        this.trade = trade;
        this.post = post;
        this.posts = posts;
        this.spec = NodeSpec.of(id, DispatchContent.ID,
                new WorkSite.AtBlock(post.seat()), post.stances(), this)
            .effort(new WorkEffort.PerTick(1.0D / 20.0D))
            .vocation(trade)
            .gesture(WorkGesture.SIT)
            .doing(DispatchContent.KEEPING_POST)
            .focus(post.seat().cell())
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        posts.keeping(post);
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        return post.seat().in(level)
            && PackageNetworks.get().sitting(level, post.seat().block(level)).isEmpty();
    }

    // Sitting down is taking the post, so the first hold sits; once up again the post is left, never retaken.
    @Override
    public boolean holds(ServerLevel level, Worker who) {
        PackageNetwork network = PackageNetworks.get();
        if (!tried) {
            tried = true;
            Optional<UUID> sitting = network.sitting(level, post.seat().block(level));
            sat = sitting.isPresent()
                ? sitting.get().equals(who.resident().id())
                : network.sit(level, post.seat().block(level), who.body());
        }
        return sat && network.sitting(level, post.seat().block(level))
            .filter(who.resident().id()::equals)
            .isPresent();
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!sat) {
            return Outcome.failed(DispatchRefusal.SEAT_TAKEN);
        }
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(trade, 1)));
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        posts.left(post.seat());
    }
}
