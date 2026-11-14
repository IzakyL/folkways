package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.item.api.Goods;
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
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

final class Providers {

    private final Colony colony;
    private final ColonyRecipes recipes;
    private final Map<Devices.NetworkKey, ColonyProvider> providers = new HashMap<>();
    private final DeviceSettings settings;
    private final Map<String, Optional<Offer>> built = new HashMap<>();
    private List<String> references = List.of();
    private long tags = -1;
    private NetworkBuffers buffers;
    private List<Offer> offers = List.of();
    private final Map<Devices.NetworkKey, Filtered> filtered = new HashMap<>();

    // A colony recipe as a pattern, with the goods it makes, which a device's filter must admit.
    private record Offer(IPatternDetails pattern, List<ItemSpec> outputs) {
    }

    Providers(Colony colony, DeviceSettings settings) {
        this.colony = colony;
        this.settings = settings;
        recipes = new ColonyRecipes(colony);
    }

    void tick(MinecraftServer server) {
        if (buffers == null) {
            migrateBuffers(server);
            buffers = new NetworkBuffers(colony, ResourceLocation.fromNamespaceAndPath(
                "folkways_core", "ae2_network_buffers"), server, recipes);
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
                    access.add(new NetworkBuffers.Access(port, port.node().getGrid(), port.delivery(), port::cancel));
                });
            }
        }
        filtered.keySet().retainAll(ports.keySet());
        providers.entrySet().removeIf(entry -> {
            NetworkPort port = ports.get(entry.getKey());
            if (port != null && port.node() == entry.getValue().anchor) {
                return false;
            }
            entry.getValue().destroy();
            return true;
        });
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
                offers = recipes.entries().stream().map(entry -> built.computeIfAbsent(entry.reference(),
                    ignored -> entry.workshop().site().where().level(server).flatMap(level ->
                        ColonyPattern.of(entry.reference(), entry.recipe(), level)).map(pattern ->
                            new Offer(pattern, entry.recipe().outputs().stream().map(Amount::spec).toList()))))
                    .flatMap(Optional::stream).distinct().toList();
                references = current;
            }
        }
        buffers.tick(access);
        for (var entry : ports.entrySet()) {
            var provider = providers.computeIfAbsent(entry.getKey(), ignored ->
                new ColonyProvider(entry.getValue().node().getLevel(), entry.getValue(), buffers));
            provider.patterns(offered(entry.getKey(), commissioning.get(entry.getKey())));
        }
    }

    // The prototype saved keys but no recipe identity. Preserve those inputs without reading its CPU/task link.
    private void migrateBuffers(MinecraftServer server) {
        var owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "ae2_network_buffers");
        var saved = colony.kept(owner);
        boolean changed = false;
        for (var raw : saved.getList("batches", Tag.TAG_COMPOUND)) {
            var batch = (CompoundTag) raw;
            if (batch.hasUUID("id")) {
                continue;
            }
            var goods = new ListTag();
            for (var amount : batch.getList("goods", Tag.TAG_COMPOUND)) {
                var input = (CompoundTag) amount;
                var key = AEKey.fromTagGeneric(server.registryAccess(), input.getCompound("key"));
                if (key instanceof AEItemKey item) {
                    NetworkBuffers.stacks(item.toStack(), input.getLong("count"))
                        .forEach(stack -> goods.add(stack.save(server.registryAccess())));
                }
            }
            batch.putUUID("id", UUID.randomUUID());
            batch.putString("recipe", "");
            batch.remove("task");
            batch.put("goods", goods);
            changed = true;
        }
        if (changed) {
            colony.keep(owner, saved);
        }
    }

    // Recomputed only when the recipes or a commissioning device's settings change.
    private List<IPatternDetails> offered(Devices.NetworkKey network, List<WorldPos> through) {
        List<Object> key = new ArrayList<>();
        through.forEach(at -> key.addAll(List.of(at, settings.at(at))));
        Filtered cached = filtered.get(network);
        if (cached != null && cached.offers() == offers && cached.key().equals(key)) {
            return cached.patterns();
        }
        var rules = through.stream().map(at -> settings.at(at).admit(at)).toList();
        List<IPatternDetails> patterns = offers.stream().filter(offer -> rules.stream().anyMatch(rule ->
            offer.outputs().stream().allMatch(goods -> rule.map(admit -> admit.admits(goods)).orElse(true))))
            .map(Offer::pattern).distinct().toList();
        filtered.put(network, new Filtered(offers, key, patterns));
        return patterns;
    }

    private record Filtered(List<Offer> offers, List<Object> key, List<IPatternDetails> patterns) {
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
        providers.values().forEach(ColonyProvider::destroy);
        providers.clear();
        if (buffers != null) {
            buffers.close();
        }
    }
}
