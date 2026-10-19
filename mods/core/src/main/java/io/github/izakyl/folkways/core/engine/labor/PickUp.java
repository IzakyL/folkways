package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

final class PickUp implements Node {

    private final UUID drop;
    private final NodeSpec spec;

    PickUp(UUID drop, WorldPos at, ItemSpec what, long count, Stances stances) {
        this.drop = drop;
        this.spec = NodeSpec.of(nameOf(drop), Salvage.DOMAIN, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(0))
            .gives(new Amount(what, count))
            .doing(Doings.PICKING_UP, what)
            .vocation(hauling())
            .gesture(WorkGesture.SWING)
            .focus(at.cell())
            .estimate(0)
            .done();
    }

    static UUID nameOf(UUID drop) {
        return UUID.nameUUIDFromBytes(("salvage|" + drop).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<Vocation> hauling() {
        return Vocations.of(Vocations.HAULING);
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return lying(level).isPresent();
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return lying(level).isPresent() ? Optional.empty() : Optional.of(LaborRefusal.GOODS_GONE);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Optional<ItemEntity> found = lying(level);
        if (found.isEmpty()) {
            return Outcome.failed(LaborRefusal.GOODS_GONE);
        }
        ItemEntity entity = found.get();
        ItemStack taken = entity.getItem().copy();
        entity.discard();
        return new Outcome.Done(List.of(WorkNoise.stowed()), List.of(taken), Optional.empty());
    }

    private Optional<ItemEntity> lying(ServerLevel level) {
        return level.getEntity(drop) instanceof ItemEntity entity
            && entity.isAlive() && !entity.getItem().isEmpty()
            ? Optional.of(entity)
            : Optional.empty();
    }
}
