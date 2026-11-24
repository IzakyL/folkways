package io.github.izakyl.folkways.vanilla.create.dispatch;

import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetwork;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class KeeperPosts {

    private static final int[] KEEPER_LEVELS = {0, 1};

    static PostKey key(UUID network, WorldPos seat) {
        return new PostKey(DispatchContent.ID, network, seat.toString());
    }

    static Post post(PostKey key, WorldPos seat, Stances approach, PackageNetwork network, Runnable changed) {
        return new Post(key, new WorkSite.AtBlock(seat), approach, ref -> false,
            new Post.Demand(Post.Staffing.STANDING), new Desk(seat, network, changed));
    }

    static List<BlockPos> seatsBeside(PackageNetwork network, ServerLevel level, BlockPos desk) {
        List<BlockPos> seats = new ArrayList<>();
        for (int down : KEEPER_LEVELS) {
            seats.addAll(network.seatsBeside(level, desk.below(down)));
        }
        return seats;
    }

    private record Desk(WorldPos seat, PackageNetwork network, Runnable changed) implements Post.Station {

        @Override
        public void describe(NodeSpec.Builder spec) {
            spec.effort(new WorkEffort.PerTick(1.0D / 20.0D))
                .vocation(DispatchContent.trade()).gesture(WorkGesture.SIT)
                .doing(DispatchContent.KEEPING_POST).focus(seat.cell());
        }

        @Override
        public Set<WorldPos> reachFrom(ServerLevel level, Post post) {
            return Set.of(seat);
        }

        @Override
        public boolean available(ServerLevel level, Post post) {
            return seat.in(level) && network.sitting(level, seat.block(level)).isEmpty();
        }

        @Override
        public boolean take(ServerLevel level, Post post, Mob body) {
            if (present(level, post, body) || network.sit(level, seat.block(level), body)) {
                changed.run();
                return true;
            }
            return false;
        }

        @Override
        public boolean present(ServerLevel level, Post post, Mob body) {
            return seat.in(level) && network.sitting(level, seat.block(level)).filter(body.getUUID()::equals).isPresent();
        }

        @Override
        public void leave(Mob body) {
            if (body.level() instanceof ServerLevel level && present(level, null, body)) {
                Seating.rise(body);
            }
            changed.run();
        }

        @Override
        public Outcome finish(Worker worker, boolean staffed, boolean demanded) {
            return staffed ? new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(DispatchContent.trade(), 1)))
                : Outcome.failed(DispatchRefusal.SEAT_TAKEN);
        }
    }
}
