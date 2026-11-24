package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.inventory.internal.Stores;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDepot;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkExtensions;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.shell.crafting.NetworkSweep;
import io.github.izakyl.folkways.core.shell.crafting.OrderedOutputs;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

final class NetworkPresence {

    static final ResourceLocation ORDERED_OUTPUTS = ResourceLocation.fromNamespaceAndPath(
        "folkways_core", "rs2_ordered_outputs");

    private final Colony colony;
    private final Optional<NetworkExtensions.Attachment<NetworkPort, Network>> attachment;
    private final Map<Rs2Devices.NetworkKey, UUID> keys = new HashMap<>();
    private final NetworkSweep<NetworkPort> sweep;
    private Tally tally;
    private OrderedOutputs orderedOutputs;
    private final PatternTimes times;
    private final Calculations calculations;
    private final Map<Rs2Devices.NetworkKey, String> savedKeys = new HashMap<>();
    final NetworkDeviceSettings settings;
    private final Rs2DevicePanel panel;
    // The last sweep's networks, by each enrolled device; the panel reads them between sweeps.
    Map<WorldPos, NetworkPanel.Network> networks = Map.of();

    NetworkPresence(Colony colony) {
        this.colony = colony;
        settings = new NetworkDeviceSettings(colony, ResourceLocation.fromNamespaceAndPath("folkways_core",
            "rs2_network_devices"));
        panel = new Rs2DevicePanel(colony, this);
        attachment = Rs2Integration.ACCESS.attach(colony, settings);
        sweep = new NetworkSweep<>(colony, Rs2Integration.OWNER, settings, Rs2Devices::at);
        times = new PatternTimes(colony,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_pattern_times"));
        calculations = new Calculations(times);
    }

    Optional<NetworkExtensions.Attachment<NetworkPort, Network>> attachment() {
        return attachment;
    }

    void tick(MinecraftServer server) {
        attachment.ifPresent(extension -> extension.tick(server, sweep.access(server,
            port -> new NetworkExtensions.Access<>(port, port.node().getNetwork(), port.delivery(), port::cancel))));
        tally = Tally.of(server);
        tally.clean();
        long now = server.overworld().getGameTime();
        if (!sweep.due(now)) {
            return;
        }
        if (orderedOutputs == null) {
            orderedOutputs = new OrderedOutputs(colony, server, ORDERED_OUTPUTS);
        }
        Set<Rs2Devices.NetworkKey> seen = new HashSet<>();
        Set<String> savedNetworks = new HashSet<>();
        Map<WorldPos, NetworkPanel.Network> swept = new HashMap<>();
        sweep.publish(server, (view, foundPorts) -> {
            var level = view.level();
            Map<Rs2Devices.NetworkKey, List<NetworkDepot.Device>> devices = new LinkedHashMap<>();
            Map<Rs2Devices.NetworkKey, NetworkPort> ports = new LinkedHashMap<>();
            Map<Rs2Devices.NetworkKey, List<WorldPos>> members = new LinkedHashMap<>();
            for (var entry : foundPorts.entrySet()) {
                WorldPos at = entry.getKey();
                NetworkPort port = entry.getValue();
                if (!StoreAccess.usability(level, port).usable()) {
                    continue;
                }
                orderedOutputs.returnAt(at, stack -> port.storage().insert(ItemResource.ofItemStack(stack),
                    stack.getCount(), Action.EXECUTE, Rs2Devices.source(port.node())));
                Rs2Devices.NetworkKey key = (Rs2Devices.NetworkKey) port.identity().key();
                seen.add(key);
                ports.putIfAbsent(key, port);
                members.computeIfAbsent(key, ignored -> new ArrayList<>()).add(at);
                NetworkDeviceSettings.NetworkDevice device = settings.at(at);
                Stances.of(level, Reach.workableCells(level, at.block(level))).ifPresent(stances ->
                    devices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new NetworkDepot.Device(at, stances,
                        port.operations().contains(Port.Operation.TAKE) && StoreRule.takes(Stores.rulesAt(at)),
                        port.crafts() && device.order(), port.filter().map(item -> Goods.specOf(
                            item.toItemStack())), port.fuzzy(), device.admit(at))));
            }
            // RS2 has no crafting CPUs and runs any number of tasks; the colony's own orders are already one at a
            // time per network, so its allowance is whether it may run that one task (default) or none.
            for (var entry : members.entrySet()) {
                var network = new NetworkPanel.Network(entry.getValue(), 1, 1,
                    tally.crafting(colony.id(), entry.getKey()), true);
                entry.getValue().forEach(at -> swept.put(at, network));
            }
            List<Workshop> workshops = new ArrayList<>();
            for (var entry : devices.entrySet()) {
                Rs2Devices.NetworkKey key = entry.getKey();
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
                workshops.add(new Depot(keys.computeIfAbsent(key, ignored -> UUID.randomUUID()),
                    new WorkSite.AtBlock(entry.getValue().getFirst().at()), key, tally, colony,
                    orderedOutputs, craftables.stream().map(item -> Goods.specOf(
                        item.toItemStack())).collect(Collectors.toUnmodifiableSet()),
                    executors, List.copyOf(entry.getValue()), calculations.quotes.snapshot(),
                    NetworkPanel.limit(settings, swept.get(entry.getValue().getFirst().at()))));
            }
            return workshops;
        });
        networks = Map.copyOf(swept);
        keys.keySet().retainAll(seen);
        savedKeys.keySet().retainAll(seen);
        calculations.retain(seen, savedNetworks);
    }

    void completed(Rs2Devices.NetworkKey network, Map<String, Long> runs, long elapsed) {
        String saved = savedKeys.get(network);
        if (saved != null) {
            times.completed(saved, runs, elapsed);
            calculations.quotes.invalidate(network);
        }
    }

    Map<String, Long> patternRuns(Rs2Devices.NetworkKey network,
                                ItemSpec item, long count) {
        return calculations.quotes.snapshot().find(network, item, count)
            .map(CraftQuotes.Quote::patterns).orElse(Map.of());
    }

    void changed() {
        sweep.changed();
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

    void close() {
        attachment.ifPresent(NetworkExtensions.Attachment::close);
        calculations.close();
        if (tally != null) {
            tally.close(colony.id());
        }
        sweep.close();
        keys.clear();
    }
}
