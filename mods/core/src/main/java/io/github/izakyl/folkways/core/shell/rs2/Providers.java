package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.network.Network;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkExtensions;
import io.github.izakyl.folkways.core.shell.rs2.NetworkPort;
import io.github.izakyl.folkways.core.shell.rs2.Rs2Devices;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public final class Providers implements NetworkExtensions.Attachment<NetworkPort, Network>, ColonyPanel {

    private static final ResourceLocation TASKS = ResourceLocation.fromNamespaceAndPath(
        "folkways_core", "rs2_provider_tasks");
    private final Colony colony;
    private final ColonyRecipes recipes;
    private final Map<WorldPos, ColonyProvider> byDevice = new HashMap<>();
    private final List<ColonyProvider> all = new ArrayList<>();
    private final Map<String, Optional<Pattern>> built = new HashMap<>();
    private List<String> references = List.of();
    private long tags = -1;
    private NetworkBuffers buffers;
    private final NetworkDeviceSettings settings;
    private Map<Pattern, String> patterns = Map.of();
    private Map<Pattern, List<ItemSpec>> outputs = Map.of();
    private final Map<Rs2Devices.NetworkKey, Filtered> filtered = new HashMap<>();

    private record Filtered(Map<Pattern, String> patterns, List<Object> key, Map<Pattern, String> offered) {
    }

    Providers(Colony colony, NetworkDeviceSettings settings) {
        this.colony = colony;
        this.settings = settings;
        recipes = new ColonyRecipes(colony);
    }

    @Override
    public void tick(MinecraftServer server, List<NetworkExtensions.Access<NetworkPort, Network>> devices) {
        if (buffers == null) {
            buffers = new NetworkBuffers(colony, ResourceLocation.fromNamespaceAndPath(
                "folkways_core", "rs2_network_buffers"), server, recipes);
            for (var raw : colony.kept(TASKS).getList("providers", Tag.TAG_COMPOUND)) {
                all.add(ColonyProvider.load((CompoundTag) raw, buffers));
            }
        }
        Map<Rs2Devices.NetworkKey, NetworkPort> ports = new LinkedHashMap<>();
        Map<Rs2Devices.NetworkKey, List<WorldPos>> commissioning = new HashMap<>();
        List<NetworkBuffers.Access> access = new ArrayList<>();
        for (var device : devices) {
            NetworkPort port = device.port();
            WorldPos at = port.identity().home();
            if (settings.at(at).commission()) {
                var key = (Rs2Devices.NetworkKey) port.identity().key();
                ports.putIfAbsent(key, port);
                commissioning.computeIfAbsent(key, ignored -> new ArrayList<>()).add(at);
            }
            access.add(new NetworkBuffers.Access(port, device.grid(), device.delivery(), task -> {
                device.cancel().accept(task);
                all.stream().filter(provider -> provider.getTaskStatuses().stream()
                    .anyMatch(status -> status.info().id().id().equals(task)))
                    .forEach(provider -> provider.cancelTask(
                        new com.refinedmods.refinedstorage.api.autocrafting.task.TaskId(task)));
            }));
        }
        if (server.overworld().getGameTime() % 20 == 0) {
            recipes.refresh(server);
            List<String> current = recipes.entries().stream().map(ColonyRecipes.Entry::reference).toList();
            if (tags != Goods.tagGeneration()) {
                // Patterns list tag members as choices; a tag reload invalidates them.
                built.clear();
                references = List.of();
                tags = Goods.tagGeneration();
            }
            if (!references.equals(current)) {
                built.keySet().retainAll(Set.copyOf(current));
                Map<Pattern, String> offered = new LinkedHashMap<>();
                Map<Pattern, List<ItemSpec>> made = new HashMap<>();
                recipes.entries().forEach(entry -> built.computeIfAbsent(entry.reference(),
                    ignored -> ColonyPattern.of(entry.reference(), entry.recipe())).ifPresent(pattern -> {
                        offered.put(pattern, entry.reference());
                        made.put(pattern, entry.recipe().outputs().stream().map(Amount::spec).toList());
                    }));
                patterns = Map.copyOf(offered);
                outputs = Map.copyOf(made);
                references = current;
            }
        }
        buffers.tick(access);
        filtered.keySet().retainAll(ports.keySet());
        Map<Rs2Devices.NetworkKey, ColonyProvider> attached = new LinkedHashMap<>();
        byDevice.clear();
        for (var entry : ports.entrySet()) {
            NetworkPort port = entry.getValue();
            var network = port.node().getNetwork();
            List<ColonyProvider> candidates = all.stream().filter(provider -> !attached.containsValue(provider))
                .filter(provider -> provider.getNetwork() == network
                    || provider.anchor != null && provider.anchor.getNetwork() == network
                    || access.stream().filter(at -> at.network() == network)
                        .anyMatch(at -> provider.devices.contains(at.port().identity().home())))
                .toList();
            ColonyProvider provider;
            if (candidates.isEmpty()) {
                provider = new ColonyProvider(port.identity().home(), buffers);
                all.add(provider);
            } else {
                provider = candidates.getFirst();
                for (ColonyProvider merged : candidates.subList(1, candidates.size())) {
                    List<Task> tasks =
                        List.copyOf(merged.tasks());
                    merged.destroy();
                    all.remove(merged);
                    tasks.forEach(provider::addTask);
                    provider.devices.addAll(merged.devices);
                }
            }
            provider.connect(port);
            access.stream().filter(at -> at.network() == network)
                .forEach(at -> provider.devices.add(at.port().identity().home()));
            var terminals = commissioning.get(entry.getKey());
            provider.patterns(offered(entry.getKey(), terminals));
            terminals.forEach(at -> byDevice.put(at, provider));
            attached.put(entry.getKey(), provider);
        }
        for (ColonyProvider provider : List.copyOf(all)) {
            if (attached.containsValue(provider)) {
                provider.doWork();
            } else {
                provider.detach();
                if (provider.tasks().isEmpty()) {
                    provider.destroy();
                    all.remove(provider);
                }
            }
        }
        save();
    }

    // Recomputed only when the recipes or a commissioning device's settings change.
    private Map<Pattern, String> offered(Rs2Devices.NetworkKey network, List<WorldPos> through) {
        List<Object> key = new ArrayList<>();
        through.forEach(at -> key.addAll(List.of(at, settings.at(at))));
        Filtered cached = filtered.get(network);
        if (cached != null && cached.patterns() == patterns && cached.key().equals(key)) {
            return cached.offered();
        }
        var rules = through.stream().map(at -> settings.at(at).admit(at)).toList();
        Map<Pattern, String> offered = new LinkedHashMap<>();
        patterns.forEach((pattern, reference) -> {
            if (rules.stream().anyMatch(rule -> outputs.getOrDefault(pattern, List.of()).stream()
                    .allMatch(goods -> rule.map(admit -> admit.admits(goods)).orElse(true)))) {
                offered.put(pattern, reference);
            }
        });
        filtered.put(network, new Filtered(patterns, key, Map.copyOf(offered)));
        return filtered.get(network).offered();
    }

    private void save() {
        var saved = new ListTag();
        all.stream().filter(provider -> !provider.tasks().isEmpty()).forEach(provider -> saved.add(provider.save()));
        var data = new CompoundTag();
        data.put("providers", saved);
        if (!data.equals(colony.kept(TASKS))) {
            colony.keep(TASKS, data);
        }
    }

    List<Amount> keeps(UUID resident) {
        return buffers == null ? List.of() : buffers.keeps(resident);
    }

    @Override
    public List<Card.Row> rows(Page page, ColonyView view) {
        return buffers == null ? List.of() : buffers.rows(page, view);
    }

    public Optional<ColonyProvider> at(WorldPos device) {
        return Optional.ofNullable(byDevice.get(device));
    }

    @Override
    public void close() {
        save();
        all.forEach(ColonyProvider::destroy);
        all.clear();
        byDevice.clear();
        if (buffers != null) {
            buffers.close();
        }
    }
}
