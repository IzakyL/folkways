package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

public final class PickUp implements Node {

    public static final net.minecraft.resources.ResourceLocation DOMAIN =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways_core", "salvage");

    private static final Map<ItemEntity, LinkedHashSet<PickUp>> WATCHED = new IdentityHashMap<>();

    private final UUID drop;
    private ItemEntity watched;
    private Consumer<RefusalKind> fail;
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
    public Readiness readiness(ServerLevel level) {
        return Readiness.of(lying(level).isPresent());
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level, Consumer<RefusalKind> fail) {
        Optional<ItemEntity> entity = lying(level);
        if (entity.isEmpty()) {
            return Optional.of(LaborRefusal.GOODS_GONE);
        }
        watched = entity.get();
        this.fail = fail;
        WATCHED.computeIfAbsent(watched, ignored -> new LinkedHashSet<>()).add(this);
        return Optional.empty();
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        stopWatching();
    }

    private void stopWatching() {
        if (watched != null) {
            WATCHED.computeIfPresent(watched, (entity, pickups) -> {
                pickups.remove(this);
                return pickups.isEmpty() ? null : pickups;
            });
            watched = null;
        }
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(PickUp::left);
    }

    private static void left(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof ItemEntity drop)) {
            return;
        }
        var pickups = WATCHED.remove(drop);
        if (pickups != null) {
            for (PickUp pickup : pickups) {
                pickup.watched = null;
                pickup.fail.accept(LaborRefusal.GOODS_GONE);
            }
        }
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Optional<ItemEntity> found = lying(level);
        if (found.isEmpty()) {
            return Outcome.failed(LaborRefusal.GOODS_GONE);
        }
        ItemEntity entity = found.get();
        ItemStack taken = entity.getItem().copy();
        // A successful collection removes the drop itself before the runtime receives the outcome.
        stopWatching();
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
