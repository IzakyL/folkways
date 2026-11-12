package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.core.shell.crafting.DeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

final class Providers {

    private static final ResourceLocation TASKS = ResourceLocation.fromNamespaceAndPath(
        "folkways_core", "rs2_provider_tasks");
    private final Colony colony;
    private final ColonyRecipes recipes;
    private final Map<Devices.NetworkKey, ColonyProvider> providers = new HashMap<>();
    private final List<ColonyProvider> all = new ArrayList<>();
    private NetworkBuffers buffers;
    private final DeviceSettings settings;
    private Map<Pattern, String> patterns = Map.of();
    private Map<Pattern, List<ItemSpec>> outputs = Map.of();
    private final Map<Devices.NetworkKey, Filtered> filtered = new HashMap<>();

    private record Filtered(Map<Pattern, String> patterns, List<Object> key, Map<Pattern, String> offered) {
    }

    Providers(Colony colony, DeviceSettings settings) {
        this.colony = colony;
        this.settings = settings;
        recipes = new ColonyRecipes(colony);
    }

    void tick(MinecraftServer server) {
        if (buffers == null) {
            buffers = new NetworkBuffers(colony, ResourceLocation.fromNamespaceAndPath(
                "folkways_core", "rs2_network_buffers"), server, recipes);
            for (var raw : colony.kept(TASKS).getList("providers", Tag.TAG_COMPOUND)) {
                all.add(ColonyProvider.load((CompoundTag) raw, buffers));
            }
        }
        Map<Devices.NetworkKey, NetworkPort> ports = new LinkedHashMap<>();
        Map<Devices.NetworkKey, List<WorldPos>> commissioning = new HashMap<>();
        List<NetworkBuffers.Access> access = new ArrayList<>();
        for (var view : colony.views(server)) {
            var level = view.level();
            for (var at : colony.storesIn(view.dimension())) {
                if (!level.isLoaded(at.block(level))) {
                    continue;
                }
                Devices.at(level, at.block(level)).filter(port -> port.usability().usable()).ifPresent(port -> {
                    // The network commissions residents only through a device whose panel allows it.
                    if (settings.at(at).commission()) {
                        ports.putIfAbsent((Devices.NetworkKey) port.identity().key(), port);
                        commissioning.computeIfAbsent((Devices.NetworkKey) port.identity().key(),
                            ignored -> new ArrayList<>()).add(at);
                    }
                    access.add(new NetworkBuffers.Access(port, port.node().getNetwork(), port.delivery(), task -> {
                        port.cancel(task);
                        all.stream().filter(provider -> provider.getTaskStatuses().stream()
                            .anyMatch(status -> status.info().id().id().equals(task)))
                            .forEach(provider -> provider.cancelTask(
                                new com.refinedmods.refinedstorage.api.autocrafting.task.TaskId(task)));
                    }));
                });
            }
        }
        if (server.overworld().getGameTime() % 20 == 0) {
            recipes.refresh(server);
            Map<Pattern, String> offered = new LinkedHashMap<>();
            Map<Pattern, List<ItemSpec>> made = new HashMap<>();
            recipes.entries().forEach(entry -> {
                Pattern pattern = ColonyPattern.of(entry);
                offered.put(pattern, entry.reference());
                made.put(pattern, entry.recipe().outputs().stream().map(Amount::spec).toList());
            });
            patterns = Map.copyOf(offered);
            outputs = Map.copyOf(made);
        }
        buffers.tick(access);
        filtered.keySet().retainAll(ports.keySet());
        Map<Devices.NetworkKey, ColonyProvider> attached = new LinkedHashMap<>();
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
            provider.patterns(offered(entry.getKey(), commissioning.get(entry.getKey())));
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
        providers.clear();
        providers.putAll(attached);
        save();
    }

    // Recomputed only when the recipes or a commissioning device's settings change.
    private Map<Pattern, String> offered(Devices.NetworkKey network, List<WorldPos> through) {
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

    Optional<Board> board(ResourceLocation page, ColonyView view) {
        return buffers == null ? Optional.empty() : buffers.board(page, view);
    }

    Optional<ColonyProvider> at(WorldPos device) {
        return providers.values().stream().filter(provider -> provider.device.equals(device)).findFirst();
    }

    void close() {
        save();
        all.forEach(ColonyProvider::destroy);
        all.clear();
        providers.clear();
        if (buffers != null) {
            buffers.close();
        }
    }
}
