package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
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

    private final Colony colony;
    private final Map<Ae2Devices.NetworkKey, UUID> keys = new HashMap<>();
    private final NetworkSweep<NetworkPort> sweep;
    private final Optional<NetworkExtensions.Attachment<NetworkPort, IGrid>> attachment;
    private Tally tally;
    private OrderedOutputs outputs;
    private final PatternTimes times;
    private final Calculations calculations;
    private final Map<Ae2Devices.NetworkKey, String> savedKeys = new HashMap<>();
    final NetworkDeviceSettings settings;
    private final Ae2DevicePanel panel;
    // The last sweep's networks, by each enrolled device; the panel reads them between sweeps.
    Map<WorldPos, NetworkPanel.Network> networks = Map.of();

    NetworkPresence(Colony colony) {
        this.colony = colony;
        settings = new NetworkDeviceSettings(colony, ResourceLocation.fromNamespaceAndPath("folkways_core",
            "ae2_network_devices"));
        panel = new Ae2DevicePanel(colony, this);
        attachment = Ae2Integration.ACCESS.attach(colony, settings);
        sweep = new NetworkSweep<>(colony, Ae2Integration.OWNER, settings, Ae2Devices::at);
        times = new PatternTimes(colony,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "ae2_pattern_times"));
        calculations = new Calculations(times);
    }

    Optional<NetworkExtensions.Attachment<NetworkPort, IGrid>> attachment() {
        return attachment;
    }

    void tick(MinecraftServer server) {
        attachment.ifPresent(extension -> extension.tick(server, sweep.access(server,
            port -> new NetworkExtensions.Access<>(port, port.node().getGrid(), port.delivery(), port::cancel))));
        tally = Tally.of(server);
        tally.clean();
        long now = server.overworld().getGameTime();
        if (!sweep.due(now)) {
            return;
        }
        if (outputs == null) {
            outputs = new OrderedOutputs(colony, server);
        }
        Set<Ae2Devices.NetworkKey> seen = new HashSet<>();
        Set<String> savedNetworks = new HashSet<>();
        Map<WorldPos, NetworkPanel.Network> swept = new HashMap<>();
        sweep.publish(server, (view, foundPorts) -> {
            var level = view.level();
            Map<Ae2Devices.NetworkKey, List<NetworkDepot.Device>> devices = new LinkedHashMap<>();
            Map<Ae2Devices.NetworkKey, NetworkPort> ports = new LinkedHashMap<>();
            Map<Ae2Devices.NetworkKey, List<WorldPos>> members = new LinkedHashMap<>();
            foundPorts.forEach((at, port) -> {
                if (StoreAccess.usability(level, port).usable()) {
                    outputs.returnAt(at, stack -> port.storage().insert(AEItemKey.of(stack), stack.getCount(),
                        Actionable.MODULATE, Ae2Devices.source(port.node())));
                }
            });
            for (var entry : foundPorts.entrySet()) {
                WorldPos at = entry.getKey();
                NetworkPort port = entry.getValue();
                if (!StoreAccess.usability(level, port).usable()) {
                    continue;
                }
                Ae2Devices.NetworkKey key = port.craftingKey();
                seen.add(key);
                ports.putIfAbsent(key, port);
                members.computeIfAbsent(key, ignored -> new ArrayList<>()).add(at);
                NetworkDeviceSettings.NetworkDevice device = settings.at(at);
                Stances.of(level, Reach.workableCells(level, at.block(level))).ifPresent(stances ->
                    devices.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new NetworkDepot.Device(at, stances,
                        port.operations().contains(Port.Operation.TAKE) && StoreRule.takes(Stores.rulesAt(at)),
                        port.crafts() && device.order(), device.admit(at))));
            }
            for (var entry : members.entrySet()) {
                int total = ports.get(entry.getKey()).node().getGrid().getCraftingService().getCpus().size();
                var network = new NetworkPanel.Network(entry.getValue(), total, NetworkDeviceSettings.defaultCpus(total),
                    tally.crafting(colony.id(), entry.getKey()), false);
                entry.getValue().forEach(at -> swept.put(at, network));
            }
            List<Workshop> workshops = new ArrayList<>();
            for (var entry : devices.entrySet()) {
                Ae2Devices.NetworkKey key = entry.getKey();
                NetworkPort port = ports.get(key);
                var crafting = port.node().getGrid().getCraftingService();
                Set<AEItemKey> craftables = new HashSet<>();
                for (var item : crafting.getCraftables(AEItemKey.filter())) {
                    if (item instanceof AEItemKey goods) {
                        craftables.add(goods);
                    }
                }
                List<Tally.Cpu> cpus = crafting.getCpus().stream()
                    .filter(cpu -> !cpu.isBusy() && cpu.getSelectionMode() != CpuSelectionMode.PLAYER_ONLY)
                    .map(tally::snapshot).toList();
                String savedNetwork = PatternTimes.identity(
                    entry.getValue().stream().map(device -> device.at().toString()).sorted()
                        .collect(Collectors.joining(";")));
                savedKeys.put(key, savedNetwork);
                savedNetworks.add(savedNetwork);
                calculations.update(key, savedNetwork, port, craftables);
                workshops.add(new Depot(keys.computeIfAbsent(key, ignored -> UUID.randomUUID()),
                    new WorkSite.AtBlock(entry.getValue().getFirst().at()), key, tally, colony,
                    outputs, craftables.stream().map(item -> Goods.specOf(
                        item.toStack())).collect(Collectors.toUnmodifiableSet()),
                    cpus, List.copyOf(entry.getValue()), calculations.quotes.snapshot(),
                    NetworkPanel.limit(settings, swept.get(entry.getValue().getFirst().at()))));
            }
            return workshops;
        });
        networks = Map.copyOf(swept);
        keys.keySet().retainAll(seen);
        savedKeys.keySet().retainAll(seen);
        calculations.retain(seen, savedNetworks);
    }

    void changed() {
        sweep.changed();
    }

    NetworkPanel.Side panel() {
        return panel;
    }

    // Checked again when a resident submits: the panel may have changed since the plan was made.
    static boolean orders(Colony colony, WorldPos at, ItemSpec item) {
        return colony.service(Ae2Integration.OWNER, NetworkPresence.class)
            .map(presence -> presence.settings.at(at).order() && presence.settings.at(at).admits(at, item))
            .orElse(true);
    }

    void completed(Ae2Devices.NetworkKey network, Map<String, Long> runs, long elapsed) {
        String saved = savedKeys.get(network);
        if (saved != null) {
            times.completed(saved, runs, elapsed);
            calculations.quotes.invalidate(network);
        }
    }

    Map<String, Long> patternRuns(Ae2Devices.NetworkKey network,
                                ItemSpec item, long count) {
        return calculations.quotes.snapshot().find(network, item, count)
            .map(CraftQuotes.Quote::patterns).orElse(Map.of());
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
