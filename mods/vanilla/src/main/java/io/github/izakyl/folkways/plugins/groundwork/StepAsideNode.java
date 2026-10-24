package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

// Walking to the stance is the work. A resident leaves the last unfilled space through normal navigation;
// bystanders belonging to no colony and players simply keep the fill waiting until they move themselves.
final class StepAsideNode implements Node {

    private final FillPlan plan;
    private final UUID resident;
    private final BlockPos target;
    private final NodeSpec spec;

    StepAsideNode(FillPlan plan, UUID zone, Mob body, BlockPos target, ServerLevel level) {
        this.plan = plan;
        this.resident = body.getUUID();
        this.target = target;
        UUID id = UUID.nameUUIDFromBytes(("folkways:ground/aside/" + zone + "/" + resident + "/" + target)
            .getBytes(StandardCharsets.UTF_8));
        spec = NodeSpec.of(id, GroundworkContent.ID, new WorkSite.AtBlock(WorldPos.of(level, target)),
                Stances.at(level, target), Workload.Once.of(0))
            .effort(WorkEffort.NONE).doing(GroundworkContent.STEPPING_ASIDE).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.of(resident);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        // Proving a remote destination is not an arrival receipt. Keep this same task retryable
        // until the real body arrives and the same-tick proof actually consumes its obligation.
        if (!Reach.standingAt(who.body().position(), target) || !plan.clearOfFill(who.body())
                || !plan.safeDestination(who.body(), target) || !plan.movedAside(resident)) {
            return Outcome.failed(GroundRefusal.FILL_ACCESS_CHANGED);
        }
        return Outcome.done();
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == GroundRefusal.FILL_ACCESS_CHANGED && plan.provingDestination(target)
            ? Recourse.waitFor(1) : Recourse.FAIL;
    }
}
