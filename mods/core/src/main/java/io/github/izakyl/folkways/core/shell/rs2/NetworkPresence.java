package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.shell.crafting.DeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

final class NetworkPresence implements Facing {

    private final Colony colony;
    private final Providers providers;
    private final Map<Devices.NetworkKey, UUID> keys = new HashMap<>();
    private final Set<ResourceKey<Level>> dimensions = new HashSet<>();
    private Tally tally;
    private final PatternTimes times;
    private final Calculations calculations;
    private final Map<Devices.NetworkKey, String> savedKeys = new HashMap<>();
    final DeviceSettings settings;
    private final DevicePanel panel;
    // The last sweep's networks, by each enrolled device; the panel reads them between sweeps.
    Map<WorldPos, NetworkPanel.Network> networks = Map.of();
    private boolean dirty;

    NetworkPresence(Colony colony) {
        this.colony = colony;
        settings = new DeviceSettings(colony, ResourceLocation.fromNamespaceAndPath("folkways_core",
            "rs2_network_devices"));
        panel = new DevicePanel(colony, this);
        providers = new Providers(colony, settings);
        times = new PatternTimes(colony,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_pattern_times"));
        calculations = new Calculations(times);
    }

    void tick(MinecraftServer server) {
        providers.tick(server);
        tally = Tally.of(server);
        tally.clean();
        long now = server.overworld().getGameTime();
        if (now % 20 != 0 && !dirty) {
            return;
        }
        dirty = false;
        tally.observed(now);
        Set<Devices.NetworkKey> seen = new HashSet<>();
        Set<String> savedNetworks = new HashSet<>();
        Set<ResourceKey<Level>> present = new HashSet<>();
        Map<WorldPos, NetworkPanel.Network> swept = new HashMap<>();
        for (var view : colony.views(server)) {
            var level = view.level();
            Map<Devices.NetworkKey, List<Depot.Device>> devices = new LinkedHashMap<>();
            Map<Devices.NetworkKey, NetworkPort> ports = new LinkedHashMap<>();
            Map<Devices.NetworkKey, List<WorldPos>> members = new LinkedHashMap<>();
            List<WorldPos> withheld = new ArrayList<>();
            List<StoreRule> rules = new ArrayList<>();
            for (var at : colony.storesIn(view.dimension())) {
                var pos = at.block(level);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                var found = Devices.at(level, pos);
                if (found.isEmpty()) {
                    continue;
                }
                NetworkPort port = found.get();
                withheld.add(at);
                DeviceSettings.Device device = settings.at(at);
                rules.addAll(device.rules(at));
                StorePorts.changed(level, pos);
                if (!port.usability().usable()) {
                    continue;
                }
                Devices.NetworkKey key = (Devices.NetworkKey) port.identity().key();
                seen.add(key);
                ports.putIfAbsent(key, port);
                members.computeIfAbsent(key, ignored -> new ArrayList<>()).add(at);
                Stances.of(level, Reach.workableCells(level, pos)).ifPresent(stances ->
                    devices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Depot.Device(at, stances,
                        port.operations().contains(StorePort.Operation.TAKE) && device.take()
                            && StoreRule.takes(colony.storeRules(at)),
                        port.crafts() && device.order(), port.filter().map(item -> Goods.specOf(
                            item.toItemStack())), port.fuzzy(), device.admit(at))));
            }
            colony.rule(Rs2Integration.OWNER, view.dimension(), rules);
            // RS2 has no crafting CPUs and runs any number of tasks; the colony's own orders are already one at a
            // time per network, so its allowance is whether it may run that one task (default) or none.
            for (var entry : members.entrySet()) {
                var network = new NetworkPanel.Network(entry.getValue(), 1, 1,
                    tally.crafting(colony.id(), entry.getKey()), true);
                entry.getValue().forEach(at -> swept.put(at, network));
            }
            List<Workshop> workshops = new ArrayList<>();
            for (var entry : devices.entrySet()) {
                Devices.NetworkKey key = entry.getKey();
                NetworkPort port = ports.get(key);
                var crafting = port.crafting();
                Set<ItemResource> craftables = new HashSet<>();
                for (var item : crafting.getOutputs()) {
                    if (item instanceof ItemResource goods) {
                        craftables.add(goods);
                    }
                }
                Map<PatternProvider, Set<ItemResource>> outputs = new LinkedHashMap<>();
                Map<PatternProvider, Integer> steps = new HashMap<>();
                for (var pattern : crafting.getPatterns()) {
                    var provider = crafting.getProviderByPattern(pattern);
                    if (provider == null) {
                        continue;
                    }
                    for (var output : pattern.layout().outputs()) {
                        if (output.resource() instanceof ItemResource item) {
                            outputs.computeIfAbsent(provider, ignored -> new HashSet<>()).add(item);
                        }
                    }
                    steps.merge(provider, Math.max(1, provider.getSteps(pattern)), Math::min);
                }
                boolean busy = !crafting.getStatuses().isEmpty();
                List<Tally.Executor> executors = outputs.entrySet().stream()
                    .map(output -> tally.snapshot(output.getKey(), steps.get(output.getKey()), busy,
                        output.getValue().stream().map(item -> Goods.specOf(
                            item.toItemStack())).collect(Collectors.toUnmodifiableSet()))).toList();
                String savedNetwork = PatternTimes.identity(
                    entry.getValue().stream().map(device -> device.at().toString()).sorted()
                        .collect(Collectors.joining(";")));
                savedKeys.put(key, savedNetwork);
                savedNetworks.add(savedNetwork);
                calculations.update(key, savedNetwork, port, craftables);
                List<PortReader.Entry> stock = port.stock();
                workshops.add(new Depot(keys.computeIfAbsent(key, ignored -> UUID.randomUUID()),
                    new WorkSite.AtBlock(entry.getValue().getFirst().at()), key, tally, colony,
                    List.copyOf(stock), now, craftables.stream().map(item -> Goods.specOf(
                        item.toItemStack())).collect(Collectors.toUnmodifiableSet()),
                    executors, List.copyOf(entry.getValue()), calculations.quotes.snapshot(),
                    NetworkPanel.limit(settings, swept.get(entry.getValue().getFirst().at()))));
            }
            // Like AE2's Depot, one ledger answers for stock and future products alike. Withholding stops the
            // ordinary stock rule from promising a crafted output a second time before its resident collects it.
            colony.withhold(Rs2Integration.OWNER, view.dimension(), withheld);
            colony.publish(Rs2Integration.OWNER, view.dimension(), workshops);
            present.add(view.dimension());
        }
        for (var dimension : dimensions) {
            if (!present.contains(dimension)) {
                colony.withhold(Rs2Integration.OWNER, dimension, List.of());
                colony.publish(Rs2Integration.OWNER, dimension, List.of());
                colony.rule(Rs2Integration.OWNER, dimension, List.of());
            }
        }
        networks = Map.copyOf(swept);
        dimensions.clear();
        dimensions.addAll(present);
        keys.keySet().retainAll(seen);
        savedKeys.keySet().retainAll(seen);
        calculations.retain(seen, savedNetworks);
    }

    void completed(Devices.NetworkKey network, Map<String, Long> runs, long elapsed) {
        String saved = savedKeys.get(network);
        if (saved != null) {
            times.completed(saved, runs, elapsed);
            calculations.quotes.invalidate(network);
        }
    }

    Map<String, Long> patternRuns(Devices.NetworkKey network,
                                ItemSpec item, long count) {
        return calculations.quotes.snapshot().find(network, item, count)
            .map(CraftQuotes.Quote::patterns).orElse(Map.of());
    }

    void changed() {
        dirty = true;
    }

    NetworkPanel.Side panel() {
        return panel;
    }

    // Checked again when a resident submits: the panel may have changed since the plan was made.
    static boolean orders(Colony colony, WorldPos at, ItemSpec item) {
        return colony.service(Rs2Integration.OWNER, NetworkPresence.class)
            .map(presence -> presence.settings.at(at).order() && presence.settings.at(at).admits(at, item))
            .orElse(true);
    }

    Optional<ColonyProvider> provider(WorldPos at) {
        return providers.at(at);
    }

    List<Amount> keeps(UUID resident) {
        return providers.keeps(resident);
    }

    @Override
    public Optional<Board> board(ResourceLocation page,
            ColonyView view) {
        return providers.board(page, view);
    }

    void close() {
        providers.close();
        calculations.close();
        if (tally != null) {
            tally.close(colony.id());
        }
        for (var dimension : dimensions) {
            colony.withhold(Rs2Integration.OWNER, dimension, List.of());
            colony.publish(Rs2Integration.OWNER, dimension, List.of());
            colony.rule(Rs2Integration.OWNER, dimension, List.of());
        }
        dimensions.clear();
        keys.clear();
    }
}
