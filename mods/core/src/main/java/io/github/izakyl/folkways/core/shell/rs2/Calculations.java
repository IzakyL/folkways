package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Calculations {

    final CraftQuotes<Devices.NetworkKey> quotes = new CraftQuotes<>();
    private final Map<Devices.NetworkKey, State> states = new HashMap<>();
    private final PatternTimes times;
    private final Map<String, Set<String>> definitions = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "folkways-rs2-preview");
        thread.setDaemon(true);
        return thread;
    });

    Calculations(PatternTimes times) {
        this.times = times;
    }

    private static final class Token implements CancellationToken {
        private volatile boolean canceled;

        @Override
        public boolean isCancelled() {
            return canceled || Thread.currentThread().isInterrupted();
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    }

    private record Pending(CompletableFuture<Map<String, Long>> future, Token token) {
        void cancel() {
            token.cancel();
            future.cancel(true);
        }
    }

    private record Constraints(int steps, int priority) {
    }

    private static final class State {
        private long revision = -1;
        private Map<String, Constraints> patterns = Map.of();
        private final Map<CraftQuotes.Request<Devices.NetworkKey>, Pending> pending =
            new HashMap<>();
    }

    static String definition(Pattern pattern) {
        return PatternTimes.identity(pattern.id() + "/" + pattern.layout());
    }

    void update(Devices.NetworkKey network, String savedNetwork, NetworkPort port, Set<ItemResource> craftables) {
        State state = states.computeIfAbsent(network, ignored -> new State());
        var crafting = port.crafting();
        Map<String, Constraints> patterns = new HashMap<>();
        PatternRepositoryImpl repository = new PatternRepositoryImpl();
        Set<Pattern> colonyPatterns = new HashSet<>();
        for (var pattern : crafting.getPatterns()) {
            var provider = crafting.getProviderByPattern(pattern);
            // RS2 has no CPU byte limit. Every participating pattern needs a connected executor with steps.
            int steps = provider == null ? 0 : provider.getSteps(pattern);
            if (provider instanceof ColonyProvider) {
                colonyPatterns.add(pattern);
            }
            int priority = port.node().getNetwork().getComponent(
                GraphNetworkComponent.class).getContainers().stream()
                .filter(container -> container.getNode() == provider)
                .mapToInt(NetworkNodeContainer::getPriority)
                .max().orElse(0);
            patterns.put(definition(pattern), new Constraints(steps, priority));
            if (steps > 0) {
                repository.add(pattern, priority);
            }
        }
        definitions.put(savedNetwork, Set.copyOf(patterns.keySet()));
        long revision = port.revision().orElse(-1);
        if (revision != state.revision || !patterns.equals(state.patterns)) {
            state.pending.values().forEach(Pending::cancel);
            state.pending.clear();
            quotes.invalidate(network);
            state.revision = revision;
            state.patterns = Map.copyOf(patterns);
        }
        var iterator = state.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().future().isDone()) {
                continue;
            }
            CraftQuotes.Quote quote = CraftQuotes.Quote.impossible();
            try {
                Map<String, Long> runs = entry.getValue().future().join();
                if (!runs.isEmpty()) {
                    quote = new CraftQuotes.Quote(0, times.estimate(savedNetwork, runs), runs, true);
                }
            } catch (RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().debug("RS2 preview failed", failure);
            }
            quotes.put(entry.getKey(), quote);
            iterator.remove();
        }
        var snapshot = quotes.snapshot();
        craftables.forEach(item -> snapshot.find(network, Goods.specOf(item.toItemStack()), 1));
        for (var request : quotes.requested(network)) {
            if (state.pending.size() >= 4) {
                break;
            }
            if (state.pending.containsKey(request)) {
                continue;
            }
            var item = craftables.stream().filter(key -> Goods.specOf(key.toItemStack()).equals(request.item()))
                .findFirst().orElse(null);
            if (item == null) {
                quotes.put(request, CraftQuotes.Quote.impossible());
                continue;
            }
            // Copy *all* resources (including fluids) and the pattern repository on the server. RS2's native
            // getPreview reads the live network on its worker; our calculator instead owns these detached copies.
            var resources = MutableResourceListImpl.create();
            port.storage().getAll().forEach(resources::add);
            var storage = new RootStorageImpl(resources);
            var calculator = new CraftingCalculatorImpl(repository, storage);
            Token token = new Token();
            state.pending.put(request, new Pending(CompletableFuture.supplyAsync(() -> {
                var preview = PreviewCraftingCalculatorListener.calculatePreview(calculator, item, request.count(),
                    token);
                if (preview.type() != PreviewType.SUCCESS
                        || preview.items().stream().anyMatch(value -> value.missing() > 0)) {
                    return Map.of();
                }
                var plan = TaskPlanCraftingCalculatorListener.calculatePlan(calculator, item, request.count(),
                    token);
                if (plan.isPresent() && plan.get().patterns().keySet().stream().anyMatch(colonyPatterns::contains)) {
                    return Map.of();
                }
                Map<String, Long> runs = new HashMap<>();
                plan.ifPresent(value -> value.patterns().forEach((pattern, use) ->
                    runs.merge(definition(pattern), use.iterations(), Long::sum)));
                return Map.copyOf(runs);
            }, worker), token));
        }
    }

    void retain(Set<Devices.NetworkKey> networks, Set<String> savedNetworks) {
        states.entrySet().removeIf(entry -> {
            if (networks.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().pending.values().forEach(Pending::cancel);
            return true;
        });
        quotes.retain(networks);
        definitions.keySet().retainAll(savedNetworks);
        times.retain(definitions);
    }

    void close() {
        states.values().forEach(state -> state.pending.values().forEach(Pending::cancel));
        states.clear();
        worker.shutdownNow();
    }
}
