package io.github.izakyl.folkways.plugins.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

abstract class StockpotNode implements Node {

    private final NodeSpec spec;
    final WorldPos at;

    StockpotNode(NodeSpec spec, WorldPos at) {
        this.spec = spec;
        this.at = at;
    }

    static NodeSpec.Builder spec(UUID id, WorldPos at, Stances stances) {
        return NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(20, WaresContent::haste))
            .vocation(WaresContent.trade()).gesture(WorkGesture.SWING).focus(at.cell());
    }

    @Override
    public final NodeSpec spec() {
        return spec;
    }

    final Optional<StockpotBlockEntity> pot(ServerLevel level) {
        return at.in(level) && level.isLoaded(at.block(level))
            && level.getBlockEntity(at.block(level)) instanceof StockpotBlockEntity pot
            ? Optional.of(pot) : Optional.empty();
    }

    final boolean canWork(ServerLevel level, Worker who) {
        return at.in(level) && Reach.stanceHolding(who.body(), spec.stances())
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level)))
            .isPresent();
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return at.in(level) && level.isLoaded(at.block(level)) && pot(level).isEmpty()
            ? Optional.of(CookeryRefusal.NOT_A_STATION) : Optional.empty();
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == CookeryRefusal.STATION_BUSY || why == CookeryRefusal.NO_HEAT_SOURCE
            ? Recourse.waitFor(40) : Recourse.FAIL;
    }
}
