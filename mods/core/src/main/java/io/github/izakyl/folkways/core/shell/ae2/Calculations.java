package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** All native API calls and Future reads belong to the server thread. */
final class Calculations {

    final CraftQuotes<Devices.NetworkKey> quotes = new CraftQuotes<>();
    private final Map<Devices.NetworkKey, State> states = new HashMap<>();
    private final PatternTimes times;
    private final Map<String, Set<String>> definitions = new HashMap<>();

    Calculations(PatternTimes times) {
        this.times = times;
    }

    private static final class State {
        private Map<AEKey, Long> stock = Map.of();
        private Set<String> patterns = Set.of();
        private final Map<CraftQuotes.Request<Devices.NetworkKey>, Future<ICraftingPlan>> pending = new HashMap<>();
    }

    static String definition(appeng.api.crafting.IPatternDetails pattern, NetworkPort port) {
        return PatternTimes.identity(pattern.getDefinition().toTag(port.node().getLevel().registryAccess()).toString());
    }

    void update(Devices.NetworkKey network, String savedNetwork, NetworkPort port, Set<AEItemKey> craftables) {
        State state = states.computeIfAbsent(network, ignored -> new State());
        Map<AEKey, Long> stock = new HashMap<>();
        for (var entry : port.storage().getAvailableStacks()) {
            if (entry.getLongValue() > 0) {
                stock.put(entry.getKey(), entry.getLongValue());
            }
        }
        Set<String> patterns = new HashSet<>();
        var crafting = port.node().getGrid().getCraftingService();
        for (var item : craftables) {
            crafting.getCraftingFor(item).forEach(pattern -> patterns.add(definition(pattern, port)));
        }
        definitions.put(savedNetwork, Set.copyOf(patterns));
        // AE2 19's public storage API has no inventory revision. Compare the entire available inventory at the
        // same boundary as the Depot snapshot, rather than invalidating an in-flight calculation every second.
        if (!stock.equals(state.stock) || !patterns.equals(state.patterns)) {
            state.pending.values().forEach(future -> future.cancel(true));
            state.pending.clear();
            quotes.invalidate(network);
            state.stock = Map.copyOf(stock);
            state.patterns = Set.copyOf(patterns);
        }
        var iterator = state.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            CraftQuotes.Quote quote = CraftQuotes.Quote.impossible();
            try {
                var plan = entry.getValue().get();
                if (!plan.simulation() && plan.missingItems().isEmpty()
                        && plan.patternTimes().keySet().stream().noneMatch(ColonyPattern::isColonyPattern)) {
                    Map<String, Long> runs = new HashMap<>();
                    plan.patternTimes().forEach((pattern, count) ->
                        runs.merge(definition(pattern, port), count, Long::sum));
                    quote = new CraftQuotes.Quote(plan.bytes(), times.estimate(savedNetwork, runs), runs, true);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().debug("AE2 preview failed", failure);
            }
            quotes.put(entry.getKey(), quote);
            iterator.remove();
        }
        // Warm the smallest quantity tier for every candidate. Larger tiers are exact demand quantities, so
        // rounding up cannot hide a route whose available ingredients suffice for just the requested amount.
        var snapshot = quotes.snapshot();
        for (AEItemKey item : craftables) {
            snapshot.find(network, Goods.specOf(item.toStack()), 1);
        }
        for (var request : quotes.requested(network)) {
            if (state.pending.size() >= 4) {
                break;
            }
            if (state.pending.containsKey(request)) {
                continue;
            }
            AEItemKey item = craftables.stream()
                .filter(key -> Goods.specOf(key.toStack()).equals(request.item())).findFirst().orElse(null);
            if (item == null) {
                quotes.put(request, CraftQuotes.Quote.impossible());
                continue;
            }
            var source = Devices.source(port.node());
            state.pending.put(request, crafting.beginCraftingCalculation(port.node().getLevel(), () -> source,
                item, request.count(), CalculationStrategy.REPORT_MISSING_ITEMS));
        }
    }

    void retain(Set<Devices.NetworkKey> networks, Set<String> savedNetworks) {
        states.entrySet().removeIf(entry -> {
            if (networks.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().pending.values().forEach(future -> future.cancel(true));
            return true;
        });
        quotes.retain(networks);
        definitions.keySet().retainAll(savedNetworks);
        times.retain(definitions);
    }

    void close() {
        states.values().forEach(state -> state.pending.values().forEach(future -> future.cancel(true)));
        states.clear();
    }
}
