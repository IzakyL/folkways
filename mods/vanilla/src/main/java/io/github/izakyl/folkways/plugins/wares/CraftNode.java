package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;

abstract class CraftNode implements Node {

    private static final int FULL_WAIT_TICKS = 40;

    private final NodeSpec spec;
    private final WorldPos at;
    private final Block block;

    CraftNode(NodeSpec spec, WorldPos at, Block block) {
        this.spec = spec;
        this.at = at;
        this.block = block;
    }

    static NodeSpec.Builder specOf(WorldPos at, Stances stances, Workload workload) {
        return specOf(UUID.randomUUID(), at, stances, workload);
    }

    static NodeSpec.Builder specOf(UUID id, WorldPos at, Stances stances, Workload workload) {
        return NodeSpec
            .of(id, WaresContent.ID, new WorkSite.AtBlock(at), stances, workload)
            .vocation(WaresContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(at.cell());
    }

    @Override
    public final NodeSpec spec() {
        return spec;
    }

    @Override
    public final Outcome commit(ServerLevel level, Worker who) {
        if (!standing(level)) {
            return Outcome.failed(WaresRefusal.NOT_A_STATION);
        }
        return work(level, who);
    }

    abstract Outcome work(ServerLevel level, Worker who);

    // A station broken or taken away is no place to work any more: the node fails, and what it was grown for is
    // grown again elsewhere, rather than a load waiting at the empty spot for a slot that will never empty.
    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return at.in(level) && level.isLoaded(at.block(level)) && !standing(level)
            ? Optional.of(WaresRefusal.NOT_A_STATION)
            : Optional.empty();
    }

    // A slot some other hand filled empties again as the cooker works; a station gone does not come back.
    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == WaresRefusal.STATION_FULL ? Recourse.waitFor(FULL_WAIT_TICKS) : Recourse.FAIL;
    }

    final boolean standing(ServerLevel level) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockState(at.block(level)).is(block);
    }

    final Optional<Container> machine(ServerLevel level) {
        return Machines.at(level, at.block(level));
    }

    final BlockPos cell() {
        return at.cell();
    }

    final List<WorkNoise> worked() {
        return StationNoise.of(block, cell());
    }

    final Optional<Xp> earned() {
        return earned(1);
    }

    final Optional<Xp> earned(int times) {
        return Optional.of(new Xp(WaresContent.trade(), times));
    }
}
