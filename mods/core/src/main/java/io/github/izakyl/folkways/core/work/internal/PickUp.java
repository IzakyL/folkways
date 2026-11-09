package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public final class PickUp implements Node {

    public static final net.minecraft.resources.ResourceLocation DOMAIN =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways_core", "salvage");

    private final UUID drop;
    private final NodeSpec spec;

    public PickUp(UUID drop, WorldPos at, ItemSpec what, long count, Stances stances) {
        this.drop = drop;
        this.spec = NodeSpec.of(nameOf(drop), DOMAIN, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(0))
            .gives(new Amount(what, count))
            .leaves(List.of(new Amount(what, count)))
            .doing(Doings.PICKING_UP, what)
            .vocation(hauling())
            .gesture(WorkGesture.SWING)
            .focus(at.cell())
            .estimate(0)
            .done();
    }

    public static UUID nameOf(UUID drop) {
        return UUID.nameUUIDFromBytes(("salvage|" + drop).getBytes(StandardCharsets.UTF_8));
    }

    public UUID drop() { return drop; }

    private static Optional<Vocation> hauling() {
        return Vocations.of(Vocations.HAULING);
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return Leaves.unpromised(spec.leaves(), promised);
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
