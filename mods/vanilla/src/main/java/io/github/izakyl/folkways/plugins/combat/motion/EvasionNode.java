package io.github.izakyl.folkways.plugins.combat.motion;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** The current runner owns either EF root motion or its one fallback navigator, never both. */
final class EvasionNode implements Node {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "evasion");
    private final Worker worker;
    private final Colony colony;
    private final ResidentCommands.Order order;
    private final Mob foe;
    private final BlockPos destination;
    private final Vec3 direction;
    private final Predicate<Vec3> within;
    private final long deadline;
    private final NodeSpec spec;
    private CombatMotions.Session motion;
    private CombatMotions.Session sprint;
    private boolean sprintTried;
    private boolean attempted;
    private boolean closed;
    private boolean sprintBefore;
    private long repathAt;

    EvasionNode(Worker worker, Colony colony, ResidentCommands.Order order, Mob foe, BlockPos destination,
            Vec3 direction, Predicate<Vec3> within, boolean explosive, boolean ranged) {
        this.worker = worker; this.colony = colony; this.order = order; this.foe = foe;
        this.destination = destination; this.direction = direction; this.within = within;
        deadline = worker.body().level().getGameTime() + 100;
        spec = NodeSpec.of(UUID.randomUUID(), ID, WorkSite.on(worker.body()), Stances.WHEREVER,
            (Workload.Continuous) this::advance).doing(ResourceLocation.fromNamespaceAndPath("folkways",
                explosive ? "avoiding_explosion" : ranged ? "kiting" : "dodging")).done();
    }
    ResidentCommands.Order order() { return order; }
    boolean valid() {
        Mob body = worker.body();
        return !closed && body.isAlive() && !body.isRemoved() && !body.isPassenger() && body.level().getGameTime() < deadline
            && foe.isAlive() && !foe.isRemoved() && foe.level() == body.level()
            && io.github.izakyl.folkways.core.api.resident.body.Bodies.of(body).flatMap(resident -> resident.colonyId())
                .filter(colony.id()::equals).isPresent() && colony.holdingOf(worker.resident().id()).isPresent()
            && ResidentCommands.of(colony).current(worker.resident().id()).orElse(null) == order;
    }
    Urge urge() { return new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "evade/" + spec.id()), 3, true, () -> this); }
    public NodeSpec spec() { return spec; }
    public boolean ready(ServerLevel level) { return valid(); }
    public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
    private boolean advance(ServerLevel level, Worker who) {
        if (!valid()) return false;
        Mob body = who.body();
        if (!attempted) {
            attempted = true; sprintBefore = body.isSprinting();
            body.getNavigation().stop();
            motion = CombatMotions.open(who, new CombatMotions.Request(CombatMotions.Kind.DODGE, direction, this::valid, within)).orElse(null);
        }
        if (motion != null) {
            if (motion.tick()) return true;
            motion.close(); motion = null;
        }
        if (Reach.standingAt(body.position(), destination)) return false;
        if (!within.test(body.position())) return false;
        if (level.getGameTime() >= repathAt) {
            repathAt = level.getGameTime() + 10;
            if (!body.getNavigation().moveTo(destination.getX() + .5, destination.getY(), destination.getZ() + .5, 1.35)) return false;
        }
        if (!sprintTried) {
            sprintTried = true;
            sprint = CombatMotions.open(who, new CombatMotions.Request(CombatMotions.Kind.SPRINT, direction, this::valid, within)).orElse(null);
        }
        if (sprint != null && !sprint.tick()) { sprint.close(); sprint = null; }
        if (!CombatMotions.supports(who, CombatMotions.Kind.SPRINT)) body.setSprinting(true);
        body.getLookControl().setLookAt(foe);
        return true;
    }
    public void released(ServerLevel level, Worker who, Ending how) {
        closed = true;
        if (motion != null) motion.close();
        if (sprint != null) sprint.close();
        who.body().getNavigation().stop();
        if (attempted) who.body().setSprinting(sprintBefore);
    }
}
