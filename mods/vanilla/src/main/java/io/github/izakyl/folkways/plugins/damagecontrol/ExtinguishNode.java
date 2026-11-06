package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class ExtinguishNode implements Node {
    private final NodeSpec spec;
    private final WorldPos target;
    private final UUID worker;
    private final Incidents incidents;
    private final BooleanSupplier enabled;
    private final SuppressionMethod.Plan plan;
    private SuppressionSession session;
    private final Consumer<ExtinguishNode> stopped;
    private final Consumer<ServerLevel> changed;
    private Conveyance walking;
    private final Set<WorldPos> destinations;
    private Worker operator;
    private Outcome result;
    private int interaction;
    private long settleUntil = Long.MIN_VALUE;
    private boolean closed;

    ExtinguishNode(WorldPos target, UUID worker, SuppressionMethod.Plan plan, Incidents incidents,
        BooleanSupplier enabled, Consumer<ExtinguishNode> stopped, Consumer<ServerLevel> changed) {
        this.changed = changed;
        this.stopped = stopped;
        this.target = target;
        this.worker = worker;
        this.incidents = incidents;
        this.enabled = enabled;
        this.plan = plan;
        destinations = plan.destinations();
        spec = NodeSpec.of(UUID.randomUUID(), DamageControlContent.ID, new WorkSite.AtBlock(target),
            plan.stances(), (Workload.Continuous) this::advance)
            .vocation(DamageControlContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(target.cell())
            .doing(DamageControlContent.EXTINGUISHING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    WorldPos target() {
        return target;
    }

    boolean continues(ServerLevel level) {
        return !closed && enabled.getAsBoolean() && target.in(level) && level.isLoaded(target.block(level))
            && FireSurvey.fire(level.getBlockState(target.block(level)));
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.of(worker);
    }

    @Override
    public boolean ready(ServerLevel level) {
        if (result instanceof Outcome.Done) {
            return true;
        }
        return !closed && enabled.getAsBoolean() && target.in(level) && level.isLoaded(target.block(level))
            && incidents.claim(target, worker, id(),
                FolkwaysVanillaConfig.damageControlConcurrency());
    }

    private boolean advance(ServerLevel level, Worker who) {
        operator = who;
        if (closed || !enabled.getAsBoolean() || !target.in(level) || !level.isLoaded(target.block(level))) {
            result = Outcome.failed(DamageControlRefusal.NOT_LOADED);
            close();
            return false;
        }
        if (!FireSurvey.fire(level.getBlockState(target.block(level)))) {
            result = Outcome.done();
            close();
            changed.accept(level);
            return false;
        }
        if (settleUntil != Long.MIN_VALUE) {
            if (level.getGameTime() < settleUntil) {
                return true;
            }
            VanillaSmother.remove(level, target.block(level));
            result = Outcome.done();
            changed.accept(level);
            return false;
        }
        if (session == null) {
            if (!Reach.standingIn(who.body(), spec.stances())) {
                result = Outcome.failed(DamageControlRefusal.NO_REACH);
                return false;
            }
            if (interaction++ < 10) {
                return true;
            }
            session = plan.opening().get().orElse(null);
            if (session == null) {
                result = Outcome.failed(plan.refusal().get());
                return false;
            }
        }
        if (!session.valid()) {
            result = Outcome.failed(DamageControlRefusal.NOT_LOADED);
            close();
            return false;
        }
        if (!destinations.isEmpty() && !Reach.standingIn(who.body(), new Stances.Cells(destinations))) {
            if (walking == null) {
                var body = Bodies.of(who.body()).orElseThrow();
                walking = body.locomotion().along(1, DamageControlRefusal.NO_REACH);
                walking.setOut(level, who.body(), destinations);
            }
            if (walking.step(level, who.body(), destinations) instanceof Going.Failed failure) {
                result = Outcome.failed(failure.why());
                close();
                return false;
            }
            return true;
        }
        Outcome attempted = session.suppress(level, target.block(level));
        boolean performed = session.performed();
        close();
        if (performed && FireSurvey.fire(level.getBlockState(target.block(level)))) {
            settleUntil = level.getGameTime() + 5;
            changed.accept(level);
            return true;
        }
        result = attempted;
        changed.accept(level);
        return false;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (result != null) {
            return result;
        }
        if (!ready(level)) {
            return Outcome.failed(DamageControlRefusal.NOT_LOADED);
        }
        BlockPos at = target.block(level);
        if (!FireSurvey.fire(level.getBlockState(at))) {
            return Outcome.done();
        }
        if (!Reach.standingIn(who.body(), spec.stances()) || !destinations.isEmpty()
            && !Reach.standingIn(who.body(), new Stances.Cells(destinations))) {
            return Outcome.failed(DamageControlRefusal.NO_REACH);
        }
        interaction = 10;
        return advance(level, who) ? Outcome.failed(DamageControlRefusal.BUSY) : result;
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        result = Outcome.failed(why);
        return Recourse.FAIL;
    }

    Optional<RefusalKind> failure() {
        return result instanceof Outcome.Failed failed ? Optional.of(failed.why()) : Optional.empty();
    }

    void cancel() {
        closed = true;
        close();
        incidents.release(target, worker, id());
        stopped.accept(this);
    }

    void resolved(ServerLevel level) {
        result = Outcome.done();
        closed = true;
        close();
        incidents.release(target, worker, id());
        stopped.accept(this);
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        closed = true;
        close();
        incidents.release(target, worker, id());
        stopped.accept(this);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        closed = true;
        close();
        incidents.release(target, worker, id());
        stopped.accept(this);
    }

    private void close() {
        if (walking != null && operator != null && operator.body().level() instanceof ServerLevel level) {
            walking.release(level, operator.body());
            walking = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
