package io.github.izakyl.folkways.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

abstract class StockpotAction implements WorkAction {

    private final NodeSpec spec;
    final WorldPos at;

    StockpotAction(NodeSpec spec, WorldPos at) {
        this.spec = spec;
        this.at = at;
    }

    static NodeSpec.Builder spec(UUID id, WorldPos at, Stances stances) {
        return NodeSpec.of(
            id,
            CookeryContent.ID,
            new WorkSite.AtBlock(at),
            stances,
            Workload.Once.of(20, WaresContent::haste))
                .vocation(WaresContent.trade())
                .gesture(WorkGesture.SWING)
                .focus(at.cell());
    }

    public final NodeSpec spec() {
        return spec;
    }

    public UUID id() {
        return spec.id();
    }

    public abstract boolean ready(ServerLevel level);

    final Optional<StockpotBlockEntity> pot(ServerLevel level) {
        return at.in(level)
                        && level.isLoaded(at.block(level))
                        && level.getBlockEntity(at.block(level)) instanceof StockpotBlockEntity pot
                ? Optional.of(pot)
                : Optional.empty();
    }

    final boolean canWork(ServerLevel level, Worker who) {
        return io.github.izakyl.folkways.cookery.KitchenStation.canOperate(level, at, who.body())
                && WorkStances.stanceHolding(who.body(), spec.stances())
                        .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
                        .filter(
                            feet ->
                            Reach.withinReach(feet, at.block(level))
                            && Reach.inSight(level, feet, at.block(level)))
                        .isPresent();
    }

    public Optional<RefusalKind> refusal(ServerLevel level) {
        return at.in(level) && level.isLoaded(at.block(level)) && pot(level).isEmpty()
                ? Optional.of(CookeryRefusal.NOT_A_STATION)
                : Optional.empty();
    }

    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == CookeryRefusal.STATION_BUSY || why == CookeryRefusal.NO_HEAT_SOURCE
                ? Recourse.waitFor(40)
                : Recourse.FAIL;
    }
}
