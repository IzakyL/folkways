package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.minecraft.server.level.ServerLevel;

final class CraftOrder {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final WorldPos at;
    private final Colony colony;
    private final ItemSpec wanted;
    private ItemResource item;
    private long submittedAt = -1;
    private Map<String, Long> patternRuns = Map.of();
    private final Tally.Ticket ticket;
    private final CancellationToken cancellation = new CancellationToken() {
        private volatile boolean canceled;

        @Override
        public boolean isCancelled() {
            return canceled;
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    };
    private CompletableFuture<Optional<Preview>> calculation;
    private AutocraftingNetworkComponent crafting;
    private TaskId task;
    private PatternProvider taskProvider;
    private boolean completed;
    private Optional<RefusalKind> refused = Optional.empty();
    private long startedAt = -1;

    CraftOrder(WorldPos at, ItemSpec item, Tally.Ticket ticket, Colony colony) {
        this.at = at;
        this.colony = colony;
        this.wanted = item;
        this.ticket = ticket;
        ticket.order = this;
    }

    Optional<NetworkPort> port(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))
                || !colony.storesIn(level.dimension()).contains(at)) {
            return Optional.empty();
        }
        return Devices.at(level, at.block(level)).filter(port -> port.crafts() && port.usability().usable()
            && port.allowsCraft(ItemResource.ofItemStack(Goods.display(wanted)))
            && port.identity().key().equals(ticket.network));
    }

    Optional<RefusalKind> start(ServerLevel level) {
        if (calculation != null || refused.isPresent()) {
            return refused;
        }
        var port = port(level);
        if (port.isEmpty()) {
            return refuse(level, Rs2Refusal.NO_DEVICE);
        }
        if (ticket.executor == null || !ticket.nativeProvider().getTaskStatuses().isEmpty()
                || !port.get().crafting().getStatuses().isEmpty()) {
            return refuse(level, Rs2Refusal.TASK_BUSY);
        }
        try {
            item = ItemResource.ofItemStack(Goods.display(wanted));
            crafting = port.get().crafting();
            calculation = crafting.getPreview(item, ticket.count, cancellation);
            startedAt = level.getGameTime();
            return Optional.empty();
        } catch (RuntimeException failure) {
            return refuse(level, Rs2Refusal.CALCULATION_FAILED);
        }
    }

    Optional<RefusalKind> poll(ServerLevel level) {
        if (refused.isPresent() || calculation == null || completed) {
            return refused;
        }
        var port = port(level);
        if (port.isEmpty()) {
            return refuse(level, Rs2Refusal.NO_DEVICE);
        }
        if (level.getGameTime() - startedAt > Math.max(12000L, ticket.estimatedWaitTicks() * 4L)) {
            return refuse(level, Rs2Refusal.CANCELED);
        }
        if (task != null) {
            var status = crafting.getStatuses().stream().filter(value -> value.info().id().equals(task)).findFirst();
            if (status.isEmpty() || status.get().state() == TaskState.COMPLETED) {
                if (port.get().storage().get(item) < ticket.count) {
                    return refuse(level, Rs2Refusal.CANCELED);
                }
                completed = true;
                colony.service(Rs2Integration.OWNER, NetworkPresence.class)
                    .ifPresent(presence -> presence.completed(ticket.network, patternRuns,
                        Math.max(1, level.getGameTime() - submittedAt)));
            }
            return refused;
        }
        if (!calculation.isDone()) {
            return Optional.empty();
        }
        try {
            var preview = calculation.get();
            if (preview.isPresent() && (preview.get().type() == PreviewType.MISSING_RESOURCES
                    || preview.get().items().stream().anyMatch(value -> value.missing() > 0))) {
                return refuse(level, Rs2Refusal.MISSING_INPUTS);
            }
            if (preview.isEmpty() || preview.get().type() != PreviewType.SUCCESS) {
                return refuse(level, Rs2Refusal.CALCULATION_FAILED);
            }
            if (!crafting.getStatuses().isEmpty()
                    || crafting.getPatternsByOutput(item).stream()
                        .noneMatch(pattern -> crafting.getProviderByPattern(pattern) == ticket.nativeProvider())) {
                return refuse(level, Rs2Refusal.TASK_BUSY);
            }
            // Preserve RS2's native pattern ordering and calculate on the server, as startTask does.
            var calculator = new CraftingCalculatorImpl(new Patterns(crafting), port.get().storage());
            var plan = TaskPlanCraftingCalculatorListener.calculatePlan(calculator, item, ticket.count, cancellation);
            if (plan.isEmpty()) {
                return refuse(level, Rs2Refusal.SUBMISSION_FAILED);
            }
            if (plan.get().patterns().keySet().stream().anyMatch(pattern ->
                    crafting.getProviderByPattern(pattern) instanceof ColonyProvider)) {
                return refuse(level, Rs2Refusal.CALCULATION_FAILED);
            }
            taskProvider = crafting.getProviderByPattern(plan.get().rootPattern());
            if (taskProvider != ticket.nativeProvider()) {
                return refuse(level, Rs2Refusal.TASK_BUSY);
            }
            // RS2 2.0.9's startTask uses this same constructor and provider lifecycle. Submit the checked
            // plan itself, with a fresh owned ID, so no second calculation can select a colony pattern.
            var nativeTask = new TaskImpl(plan.get(), Devices.source(port.get().node()), false);
            task = nativeTask.getId();
            try {
                taskProvider.addTask(nativeTask);
            } catch (RuntimeException failure) {
                LOGGER.warn("RS2 crafting submission failed at {}", at, failure);
                return refuse(level, Rs2Refusal.SUBMISSION_FAILED);
            }
            submittedAt = level.getGameTime();
            Map<String, Long> runs = new HashMap<>();
            plan.get().patterns().forEach((pattern, use) ->
                runs.merge(Calculations.definition(pattern), use.iterations(), Long::sum));
            patternRuns = Map.copyOf(runs);
            return Optional.empty();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return refuse(level, Rs2Refusal.CALCULATION_FAILED);
        } catch (ExecutionException | RuntimeException failure) {
            LOGGER.warn("RS2 crafting calculation or submission failed at {}", at, failure);
            return refuse(level, Rs2Refusal.CALCULATION_FAILED);
        }
    }

    /** Read-only view of the live repository, used only during synchronous server-thread calculation. */
    private record Patterns(AutocraftingNetworkComponent crafting) implements PatternRepository {

        @Override
        public Collection<Pattern> getByOutput(ResourceKey resource) {
            return crafting.getPatternsByOutput(resource);
        }

        @Override
        public Set<ResourceKey> getOutputs() {
            return crafting.getOutputs();
        }

        @Override
        public Set<Pattern> getAll() {
            return crafting.getPatterns();
        }

        @Override
        public void add(Pattern pattern, int priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(Pattern pattern, int priority) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(Pattern pattern) {
            throw new UnsupportedOperationException();
        }
    }

    boolean done(ServerLevel level) {
        return poll(level).isEmpty() && completed;
    }

    private Optional<RefusalKind> refuse(ServerLevel level, RefusalKind why) {
        refused = Optional.of(why);
        cancel();
        if (at.in(level)) {
            StorePorts.changed(level, at.block(level));
        }
        return refused;
    }

    void cancel() {
        cancellation.cancel();
        if (calculation != null && !calculation.isDone()) {
            calculation.cancel(true);
        }
        if (task != null && !completed) {
            crafting.cancel(task);
            // The provider retains tasks when a network is split or the executor is removed.
            if (taskProvider != null && taskProvider.getTaskStatuses().stream()
                    .anyMatch(value -> value.info().id().equals(task))) {
                taskProvider.cancelTask(task);
            }
        }
    }
}
