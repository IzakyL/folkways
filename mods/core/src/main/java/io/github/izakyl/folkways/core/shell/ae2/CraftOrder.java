package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.google.common.collect.ImmutableSet;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.shell.crafting.CraftRefusals;
import io.github.izakyl.folkways.core.shell.crafting.NetworkExtensions;
import io.github.izakyl.folkways.core.shell.crafting.OrderedOutputs;
import io.github.izakyl.folkways.core.shell.crafting.TakeNode;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class CraftOrder implements ICraftingRequester, TakeNode.Order {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final WorldPos at;
    private final Colony colony;
    private final ItemSpec wanted;
    private final OrderedOutputs outputs;
    private AEItemKey item;
    private Map<String, Long> patternRuns = Map.of();
    private long submittedAt = -1;
    private boolean learned;
    private final Tally.Ticket ticket;
    private Future<ICraftingPlan> calculation;
    private ICraftingLink link;
    private IGridNode requesterNode;
    private OrderedOutputs.Output output;
    private Optional<RefusalKind> refused = Optional.empty();
    private long startedAt = -1;

    CraftOrder(WorldPos at, ItemSpec item, Tally.Ticket ticket, Colony colony,
               OrderedOutputs outputs) {
        this.at = at;
        this.colony = colony;
        this.wanted = item;
        this.ticket = ticket;
        this.outputs = outputs;
        ticket.cancelWith(this::cancel);
    }

    private ICraftingCPU cpu() {
        return ticket.nativeExecutor();
    }

    Optional<NetworkPort> port(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))
                || !colony.storesIn(level.dimension()).contains(at)) {
            return Optional.empty();
        }
        return Ae2Devices.at(level, at.block(level)).filter(port -> port.crafts()
            && StoreAccess.usability(level, port).usable() && port.craftingKey().equals(ticket.network()));
    }

    @Override
    public Optional<RefusalKind> start(ServerLevel level) {
        if (calculation != null || refused.isPresent()) {
            return refused;
        }
        var port = port(level);
        if (port.isEmpty()) {
            return refuse(level, CraftRefusals.AE2.noDevice());
        }
        if (ticket.executor() == null || cpu().isBusy()) {
            return refuse(level, CraftRefusals.AE2.busy());
        }
        try {
            item = AEItemKey.of(Goods.display(wanted));
            var source = Ae2Devices.source(port.get().node());
            calculation = port.get().node().getGrid().getCraftingService().beginCraftingCalculation(level,
                () -> source, item, ticket.count(), CalculationStrategy.REPORT_MISSING_ITEMS);
            startedAt = level.getGameTime();
            return Optional.empty();
        } catch (RuntimeException failure) {
            return refuse(level, CraftRefusals.AE2.calculationFailed());
        }
    }

    @Override
    public Optional<RefusalKind> poll(ServerLevel level) {
        if (refused.isPresent() || calculation == null) {
            return refused;
        }
        var access = port(level);
        if (access.isEmpty()) {
            return refuse(level, CraftRefusals.AE2.noDevice());
        }
        if (level.getGameTime() - startedAt > Math.max(12000L, ticket.estimatedWaitTicks() * 4L)) {
            return refuse(level, CraftRefusals.AE2.canceled());
        }
        if (link != null) {
            if (link.isCanceled() || link.isDone() && !output.ready(ticket.count())) {
                return refuse(level, CraftRefusals.AE2.canceled());
            }
            return Optional.empty();
        }
        if (!calculation.isDone()) {
            return Optional.empty();
        }
        try {
            ICraftingPlan plan = calculation.get();
            if (plan.simulation() || !plan.missingItems().isEmpty()
                    || plan.patternTimes().keySet().stream().anyMatch(NetworkExtensions::isExternalPattern)) {
                return refuse(level, CraftRefusals.AE2.missingInputs());
            }
            if (plan.bytes() > cpu().getAvailableStorage() || cpu().isBusy()) {
                return refuse(level, CraftRefusals.AE2.busy());
            }
            NetworkPort port = access.get();
            requesterNode = port.node();
            output = outputs.open(at, item.toStack());
            var submitted = requesterNode.getGrid().getCraftingService().submitJob(plan, this, cpu(),
                false, Ae2Devices.source(port.node()));
            if (!submitted.successful()) {
                LOGGER.debug("AE2 crafting submission refused: {} ({})",
                    submitted.errorCode(), submitted.errorDetail());
                return refuse(level, CraftRefusals.AE2.submissionFailed());
            }
            link = submitted.link();
            Map<String, Long> runs = new HashMap<>();
            plan.patternTimes().forEach((pattern, count) ->
                runs.merge(Calculations.definition(pattern, port), count, Long::sum));
            patternRuns = Map.copyOf(runs);
            submittedAt = level.getGameTime();
            return Optional.empty();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return refuse(level, CraftRefusals.AE2.calculationFailed());
        } catch (ExecutionException | RuntimeException failure) {
            LOGGER.warn("AE2 crafting calculation or submission failed at {}", at, failure);
            return refuse(level, CraftRefusals.AE2.calculationFailed());
        }
    }

    @Override
    public boolean done(ServerLevel level) {
        boolean done = poll(level).isEmpty() && link != null && link.isDone();
        if (done && !learned) {
            learned = true;
            colony.service(Ae2Integration.OWNER, NetworkPresence.class)
                .ifPresent(presence -> presence.completed(ticket.network(), patternRuns,
                    Math.max(1, level.getGameTime() - submittedAt)));
        }
        return done;
    }

    @Override
    public List<ItemStack> collect() {
        return output.collect(ticket.count());
    }

    @Override
    public IGridNode getActionableNode() {
        return requesterNode;
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return link == null ? ImmutableSet.of() : ImmutableSet.of(link);
    }

    @Override
    public long insertCraftedItems(ICraftingLink delivery, AEKey key, long amount, Actionable mode) {
        if (link == null || delivery != link || !item.equals(key)) {
            return 0;
        }
        return output.receive(amount, mode == Actionable.SIMULATE);
    }

    @Override
    public void jobStateChange(ICraftingLink changed) {
        // The collecting node polls the link when it checks readiness.
    }

    private Optional<RefusalKind> refuse(ServerLevel level, RefusalKind why) {
        cancel();
        Ports.changed(level, at.block(level));
        return refused = Optional.of(why);
    }

    void cancel() {
        if (calculation != null && !calculation.isDone()) {
            calculation.cancel(true);
        }
        if (link != null && !link.isDone() && !link.isCanceled()) {
            link.cancel();
        }
        if (output != null) {
            output.release();
        }
    }
}
