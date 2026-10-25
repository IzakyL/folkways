package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** A finite meal using the existing native food transaction, without leaving the original station. */
final class RecoveryMealNode implements Node {
    final LivingPresence living;
    final Worker worker;
    final ResidentCommands.Order order;
    final long deadline;
    private final NodeSpec spec;

    RecoveryMealNode(LivingPresence living, Worker worker, ResidentCommands.Order order) {
        this.living = living; this.worker = worker; this.order = order;
        deadline = worker.body().level().getGameTime() + 100;
        spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.on(worker.body()), Stances.WHEREVER,
            Workload.Once.of(32)).effort(WorkEffort.NONE).doing(LivingContent.EATING).done();
    }
    public NodeSpec spec() { return spec; }
    public boolean ready(ServerLevel level) { return living.valid(this); }
    public Outcome commit(ServerLevel level, Worker who) {
        if (!living.valid(this) || who.body() != worker.body() || who.pack() != worker.pack()) return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
        return new EatAction(level, living, who.resident().id(), who.body().blockPosition()).commit(level, who);
    }
    public void released(ServerLevel level, Worker who, Ending ending) { living.recoveryEnded(this); }
}
