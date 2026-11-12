package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import net.minecraft.server.level.ServerLevel;

final class CraftOrder {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final WorldPos at;
    private final Colony colony;
    private final ItemSpec wanted;
    private AEItemKey item;
    private Map<String, Long> patternRuns = Map.of();
    private long submittedAt = -1;
    private boolean learned;
    private final Tally.Ticket ticket;
    private Future<ICraftingPlan> calculation;
    private ICraftingLink link;
    private boolean submitted;
    private Optional<RefusalKind> refused = Optional.empty();
    private long startedAt = -1;

    CraftOrder(WorldPos at, ItemSpec item, Tally.Ticket ticket, Colony colony) {
        this.at = at;
        this.colony = colony;
        this.wanted = item;
        this.ticket = ticket;
        ticket.order = this;
    }

    private ICraftingCPU cpu() {
        return ticket.nativeCpu();
    }

    Optional<NetworkPort> port(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))
                || !colony.storesIn(level.dimension()).contains(at)) {
            return Optional.empty();
        }
        return Devices.at(level, at.block(level)).filter(port -> port.crafts() && port.usability().usable()
            && port.identity().key().equals(ticket.network));
    }

    Optional<RefusalKind> start(ServerLevel level) {
        if (calculation != null || refused.isPresent()) {
            return refused;
        }
        var port = port(level);
        if (port.isEmpty()) {
            return refuse(level, Ae2Refusal.NO_DEVICE);
        }
        if (ticket.cpu == null || cpu().isBusy()) {
            return refuse(level, Ae2Refusal.CPU_BUSY);
        }
        try {
            item = AEItemKey.of(Goods.display(wanted));
            var source = Devices.source(port.get().node());
            calculation = port.get().node().getGrid().getCraftingService().beginCraftingCalculation(level,
                () -> source, item, ticket.count, CalculationStrategy.REPORT_MISSING_ITEMS);
            startedAt = level.getGameTime();
            return Optional.empty();
        } catch (RuntimeException failure) {
            return refuse(level, Ae2Refusal.CALCULATION_FAILED);
        }
    }

    Optional<RefusalKind> poll(ServerLevel level) {
        if (refused.isPresent() || calculation == null) {
            return refused;
        }
        if (port(level).isEmpty()) {
            cancel();
            return refuse(level, Ae2Refusal.NO_DEVICE);
        }
        if (level.getGameTime() - startedAt > Math.max(12000L, ticket.estimatedWaitTicks() * 4L)) {
            cancel();
            return refuse(level, Ae2Refusal.CANCELED);
        }
        if (submitted) {
            if (link == null && !cpu().isBusy()) {
                long present = port(level).orElseThrow().storage().getAvailableStacks().get(item);
                if (present < ticket.count) {
                    return refuse(level, Ae2Refusal.CANCELED);
                }
            }
            if (link != null && link.isCanceled()) {
                return refuse(level, Ae2Refusal.CANCELED);
            }
            return refused;
        }
        if (!calculation.isDone()) {
            return Optional.empty();
        }
        try {
            ICraftingPlan plan = calculation.get();
            if (plan.simulation() || !plan.missingItems().isEmpty()
                    || plan.patternTimes().keySet().stream().anyMatch(ColonyPattern::isColonyPattern)) {
                return refuse(level, Ae2Refusal.MISSING_INPUTS);
            }
            if (plan.bytes() > cpu().getAvailableStorage() || cpu().isBusy()) {
                return refuse(level, Ae2Refusal.CPU_BUSY);
            }
            NetworkPort port = port(level).orElseThrow();
            var submitted = port.node().getGrid().getCraftingService().submitJob(plan, null, cpu(),
                false, Devices.source(port.node()));
            if (!submitted.successful()) {
                LOGGER.debug("AE2 crafting submission refused: {} ({})",
                    submitted.errorCode(), submitted.errorDetail());
                return refuse(level, Ae2Refusal.SUBMISSION_FAILED);
            }
            link = submitted.link();
            Map<String, Long> runs = new HashMap<>();
            plan.patternTimes().forEach((pattern, count) ->
                runs.merge(Calculations.definition(pattern, port), count, Long::sum));
            patternRuns = Map.copyOf(runs);
            submittedAt = level.getGameTime();
            this.submitted = true;
            return Optional.empty();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return refuse(level, Ae2Refusal.CALCULATION_FAILED);
        } catch (ExecutionException | RuntimeException failure) {
            LOGGER.warn("AE2 crafting calculation or submission failed at {}", at, failure);
            return refuse(level, Ae2Refusal.CALCULATION_FAILED);
        }
    }

    boolean done(ServerLevel level) {
        // AE2 returns a null link for standalone requests; those deliver directly into network storage.
        boolean done = poll(level).isEmpty() && submitted && (link != null ? link.isDone() : !cpu().isBusy());
        if (done && !learned) {
            learned = true;
            colony.service(Ae2Integration.OWNER, NetworkPresence.class)
                .ifPresent(presence -> presence.completed(ticket.network, patternRuns,
                    Math.max(1, level.getGameTime() - submittedAt)));
        }
        return done;
    }

    private Optional<RefusalKind> refuse(ServerLevel level, RefusalKind why) {
        StorePorts.changed(level, at.block(level));
        return refused = Optional.of(why);
    }

    void cancel() {
        if (calculation != null && !calculation.isDone()) {
            calculation.cancel(true);
        }
        if (link != null && !link.isDone()) {
            link.cancel();
        } else if (submitted && link == null && cpu().isBusy()) {
            var status = cpu().getJobStatus();
            if (status != null && status.crafting().what().equals(item)) {
                cpu().cancelJob();
            }
        }
    }
}
