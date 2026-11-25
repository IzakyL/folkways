package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class TakeNode<P extends ItemPort> implements Node {

    public interface Order {
        Optional<RefusalKind> start(ServerLevel level);

        Optional<RefusalKind> poll(ServerLevel level);

        boolean done(ServerLevel level);

        List<ItemStack> collect();
    }

    public record Source<P extends ItemPort>(ResourceLocation owner,
            BiFunction<ServerLevel, BlockPos, Optional<P>> locate,
            RefusalKind noDevice, RefusalKind noStock, Function<P, Object> networkKey) {

        public Source(ResourceLocation owner, BiFunction<ServerLevel, BlockPos, Optional<P>> locate,
                RefusalKind noDevice, RefusalKind noStock) {
            this(owner, locate, noDevice, noStock, port -> port.identity().key());
        }

        public TakeNode<P> take(WorldPos at, Stances stances, NetworkTally<?, ?, ?>.Ticket ticket,
                Order order, Colony colony) {
            return new TakeNode<>(this, at, stances, ticket, order, colony);
        }
    }

    private final NodeSpec spec;
    private final Source<P> source;
    private final WorldPos at;
    private final NetworkTally<?, ?, ?>.Ticket ticket;
    private final Order order;
    private final Colony colony;
    private boolean collected;

    private TakeNode(Source<P> source, WorldPos at, Stances stances, NetworkTally<?, ?, ?>.Ticket ticket,
            Order order, Colony colony) {
        this.source = source;
        this.at = at;
        this.ticket = ticket;
        this.order = order;
        this.colony = colony;
        spec = NodeSpec.of(UUID.randomUUID(), source.owner(), new WorkSite.AtBlock(at),
                stances, Workload.Once.of(10))
            .vocation(Vocations.required(Vocations.HAULING)).gives(new Amount(ticket.wanted(), ticket.count()))
            .doing(Doings.FETCHING, ticket.wanted())
            .gesture(WorkGesture.SWING).focus(at.cell())
            .estimate((int) ticket.estimatedWaitTicks()).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean replannable() {
        return false;
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        // Finished feedback can arrive after another server poll; the missing stock is now in the resident's pack.
        if (collected) {
            return Optional.empty();
        }
        Optional<RefusalKind> refused = order.poll(level);
        if (refused.isPresent()) {
            return refused;
        }
        if (port(level).isEmpty()) {
            return Optional.of(source.noDevice());
        }
        return Optional.empty();
    }

    @Override
    public Readiness readiness(ServerLevel level) {
        return Readiness.of(!collected && port(level).isPresent() && order.done(level));
    }

    private Optional<P> port(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))
                || !colony.storesIn(level.dimension()).contains(at)) {
            return Optional.empty();
        }
        return source.locate().apply(level, at.block(level)).filter(port -> StoreAccess.usability(level, port).usable()
            && port.operations().contains(Port.Operation.TAKE) && source.networkKey().apply(port).equals(ticket.network()));
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (collected) {
            return Outcome.done();
        }
        if (!readiness(level).isReady()) {
            return Outcome.failed(source.noDevice());
        }
        List<ItemStack> goods = order.collect();
        if (goods.isEmpty()) {
            return Outcome.failed(source.noStock());
        }
        collected = true;
        return new Outcome.Done(List.of(WorkNoise.stowed()), goods, Optional.empty());
    }
}
