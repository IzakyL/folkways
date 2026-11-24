package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class CraftOrder implements TakeNode.Order {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final WorldPos at;
    private final Colony colony;
    private final ItemSpec wanted;
    private final OrderedOutputs.Output output;
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

    CraftOrder(WorldPos at, ItemSpec item, Tally.Ticket ticket, Colony colony,
               OrderedOutputs outputs) {
        this.at = at;
        this.colony = colony;
        this.wanted = item;
        this.ticket = ticket;
        output = outputs.open(at, Goods.display(item));
        ticket.cancelWith(this::cancel);
    }

    Optional<NetworkPort> port(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))
                || !colony.storesIn(level.dimension()).contains(at)) {
            return Optional.empty();
        }
        return Rs2Devices.at(level, at.block(level)).filter(port -> port.crafts() && StoreAccess.usability(level, port).usable()
            && port.allowsCraft(ItemResource.ofItemStack(Goods.display(wanted)))
            && port.identity().key().equals(ticket.network()));
    }

    @Override
    public Optional<RefusalKind> start(ServerLevel level) {
        if (calculation != null || refused.isPresent()) {
            return refused;
        }
        var port = port(level);
        if (port.isEmpty()) {
            return refuse(level, CraftRefusals.RS2.noDevice());
        }
        if (ticket.executor() == null || !ticket.nativeExecutor().getTaskStatuses().isEmpty()
                || !port.get().crafting().getStatuses().isEmpty()) {
            return refuse(level, CraftRefusals.RS2.busy());
        }
        try {
            item = ItemResource.ofItemStack(Goods.display(wanted));
            crafting = port.get().crafting();
            calculation = crafting.getPreview(item, ticket.count(), cancellation);
            startedAt = level.getGameTime();
            return Optional.empty();
        } catch (RuntimeException failure) {
            return refuse(level, CraftRefusals.RS2.calculationFailed());
        }
    }

    @Override
    public Optional<RefusalKind> poll(ServerLevel level) {
        if (refused.isPresent() || calculation == null || completed) {
            return refused;
        }
        var port = port(level);
        if (port.isEmpty()) {
            return refuse(level, CraftRefusals.RS2.noDevice());
        }
        if (level.getGameTime() - startedAt > Math.max(12000L, ticket.estimatedWaitTicks() * 4L)) {
            return refuse(level, CraftRefusals.RS2.canceled());
        }
        if (task != null) {
            var status = crafting.getStatuses().stream().filter(value -> value.info().id().equals(task)).findFirst();
            if (status.isEmpty() || status.get().state() == TaskState.COMPLETED) {
                if (!output.ready(ticket.count())) {
                    return refuse(level, CraftRefusals.RS2.canceled());
                }
                completed = true;
                colony.service(Rs2Integration.OWNER, NetworkPresence.class)
                    .ifPresent(presence -> presence.completed(ticket.network(), patternRuns,
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
                return refuse(level, CraftRefusals.RS2.missingInputs());
            }
            if (preview.isEmpty() || preview.get().type() != PreviewType.SUCCESS) {
                return refuse(level, CraftRefusals.RS2.calculationFailed());
            }
            if (!crafting.getStatuses().isEmpty()
                    || crafting.getPatternsByOutput(item).stream()
                        .noneMatch(pattern -> crafting.getProviderByPattern(pattern) == ticket.nativeExecutor())) {
                return refuse(level, CraftRefusals.RS2.busy());
            }
            // Preserve RS2's native pattern ordering and calculate on the server, as startTask does.
            var calculator = new CraftingCalculatorImpl(new Patterns(crafting), port.get().storage());
            var plan = TaskPlanCraftingCalculatorListener.calculatePlan(calculator, item, ticket.count(), cancellation);
            if (plan.isEmpty()) {
                return refuse(level, CraftRefusals.RS2.submissionFailed());
            }
            if (plan.get().patterns().keySet().stream().anyMatch(pattern ->
                    NetworkExtensions.isExternalProvider(crafting.getProviderByPattern(pattern)))) {
                return refuse(level, CraftRefusals.RS2.calculationFailed());
            }
            taskProvider = crafting.getProviderByPattern(plan.get().rootPattern());
            if (taskProvider != ticket.nativeExecutor()) {
                return refuse(level, CraftRefusals.RS2.busy());
            }
            // RS2 2.0.9's startTask uses this same constructor and provider lifecycle. Submit the checked
            // plan itself, with a fresh owned ID, so no second calculation can select a colony pattern.
            var nativeTask = new OrderedTask(plan.get(), Rs2Devices.source(port.get().node()));
            task = nativeTask.getId();
            try {
                taskProvider.addTask(nativeTask);
            } catch (RuntimeException failure) {
                LOGGER.warn("RS2 crafting submission failed at {}", at, failure);
                return refuse(level, CraftRefusals.RS2.submissionFailed());
            }
            submittedAt = level.getGameTime();
            Map<String, Long> runs = new HashMap<>();
            plan.get().patterns().forEach((pattern, use) ->
                runs.merge(Calculations.definition(pattern), use.iterations(), Long::sum));
            patternRuns = Map.copyOf(runs);
            return Optional.empty();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return refuse(level, CraftRefusals.RS2.calculationFailed());
        } catch (ExecutionException | RuntimeException failure) {
            LOGGER.warn("RS2 crafting calculation or submission failed at {}", at, failure);
            return refuse(level, CraftRefusals.RS2.calculationFailed());
        }
    }

    private final class OrderedTask extends TaskImpl {

        private long remaining = ticket.count();
        private boolean canceled;

        OrderedTask(TaskPlan plan, Actor actor) {
            super(plan, actor, false);
        }

        @Override
        public boolean step(RootStorage storage, ExternalPatternSinkProvider sinks, StepBehavior behavior,
                            TaskListener listener) {
            if (canceled || remaining == 0
                    || getState() != TaskState.RUNNING && getState() != TaskState.RETURNING_INTERNAL_STORAGE) {
                return super.step(storage, sinks, behavior, listener);
            }
            // Internal patterns can insert during RUNNING; external outputs drain from task storage afterward.
            // Both paths hand the ordered quantity to the collector and send excess to native storage.
            return super.step(new RootStorageImpl() {
                @Override
                public long insert(ResourceKey resource, long amount, Action action, Actor actor) {
                    long reserved = resource.equals(item)
                        ? output.receive(Math.min(remaining, amount), action == Action.SIMULATE) : 0;
                    if (action == Action.EXECUTE) {
                        remaining -= reserved;
                    }
                    return reserved == amount ? reserved
                        : reserved + storage.insert(resource, amount - reserved, action, actor);
                }
            }, sinks, behavior, listener);
        }

        @Override
        public void cancel() {
            canceled = true;
            super.cancel();
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

    @Override
    public boolean done(ServerLevel level) {
        return poll(level).isEmpty() && completed;
    }

    private Optional<RefusalKind> refuse(ServerLevel level, RefusalKind why) {
        refused = Optional.of(why);
        cancel();
        if (at.in(level)) {
            Ports.changed(level, at.block(level));
        }
        return refused;
    }

    @Override
    public List<ItemStack> collect() {
        return output.collect(ticket.count());
    }

    void cancel() {
        output.release();
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
