package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class ExtinguishNode implements Node {
    private final NodeSpec spec;
    private final WorldPos target;
    private final UUID worker;
    private final Incidents incidents;
    private final BooleanSupplier enabled;
    private SuppressionSession session;

    ExtinguishNode(WorldPos target, UUID worker, Stances stances, Incidents incidents,
            BooleanSupplier enabled) {
        this.target = target;
        this.worker = worker;
        this.incidents = incidents;
        this.enabled = enabled;
        spec = NodeSpec.of(UUID.randomUUID(), DamageControlContent.ID, new WorkSite.AtBlock(target),
                stances, Workload.Once.of(10))
            .vocation(DamageControlContent.trade()).gesture(WorkGesture.SWING)
            .focus(target.cell()).doing(DamageControlContent.EXTINGUISHING).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.of(worker);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return enabled.getAsBoolean() && target.in(level) && level.isLoaded(target.block(level))
            && incidents.claim(target, worker,
                FolkwaysVanillaConfig.damageControlConcurrency());
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!ready(level)) {
            return Outcome.failed(DamageControlRefusal.NOT_LOADED);
        }
        BlockPos at = target.block(level);
        if (!FireSurvey.fire(level.getBlockState(at))) {
            return Outcome.done();
        }
        if (!incidents.claim(target, worker,
                FolkwaysVanillaConfig.damageControlConcurrency())) {
            return Outcome.failed(DamageControlRefusal.BUSY);
        }
        for (SuppressionMethod method : SuppressionMethods.all()) {
            if (!method.reaches(level, who, target)) {
                continue;
            }
            Optional<SuppressionSession> opened = method.open(level, who, target);
            if (opened.isEmpty()) {
                continue;
            }
            session = opened.get();
            try {
                return session.suppress(level, at);
            } finally {
                close();
            }
        }
        return Outcome.failed(DamageControlRefusal.NO_REACH);
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        close();
        incidents.release(target, worker);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        close();
        incidents.release(target, worker);
    }

    private void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
