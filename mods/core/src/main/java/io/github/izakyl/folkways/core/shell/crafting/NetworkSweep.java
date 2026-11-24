package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Refreshes the enrolled devices, their store rules, and the workshops supplied by a native network. */
public final class NetworkSweep<P extends ItemPort> {

    private final Colony colony;
    private final ResourceLocation owner;
    private final NetworkDeviceSettings settings;
    private final BiFunction<ServerLevel, BlockPos, Optional<P>> locate;
    private final Set<ResourceKey<Level>> dimensions = new HashSet<>();
    private boolean dirty;

    public NetworkSweep(Colony colony, ResourceLocation owner, NetworkDeviceSettings settings,
            BiFunction<ServerLevel, BlockPos, Optional<P>> locate) {
        this.colony = colony;
        this.owner = owner;
        this.settings = settings;
        this.locate = locate;
    }

    public <G> List<NetworkExtensions.Access<P, G>> access(MinecraftServer server,
            Function<P, NetworkExtensions.Access<P, G>> snapshot) {
        List<NetworkExtensions.Access<P, G>> access = new ArrayList<>();
        for (var view : colony.views(server)) {
            visitPorts(view, (at, port) -> {
                if (StoreAccess.usability(view.level(), port).usable()) {
                    access.add(snapshot.apply(port));
                }
            });
        }
        return List.copyOf(access);
    }

    public void changed() {
        dirty = true;
    }

    public boolean due(long now) {
        if (now % 20 != 0 && !dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    public void publish(MinecraftServer server, BiFunction<ColonyView, Map<WorldPos, P>, List<Workshop>> workshops) {
        Set<ResourceKey<Level>> present = new HashSet<>();
        for (var view : colony.views(server)) {
            var level = view.level();
            Map<WorldPos, P> ports = new LinkedHashMap<>();
            List<StoreRule> rules = new ArrayList<>();
            visitPorts(view, (at, port) -> {
                ports.put(at, port);
                rules.addAll(settings.at(at).rules(at));
                Ports.changed(level, at.block(level));
            });
            colony.rule(owner, view.dimension(), rules);
            List<Workshop> offers = new ArrayList<>();
            for (Workshop workshop : workshops.apply(view, ports)) {
                Facilities.container(level, workshop.site().where()).filter(container -> container.network())
                    .ifPresent(container -> container.withWorkshops(List.of(workshop)).projections(level, Map.of())
                        .forEach(projection -> {
                            if (projection instanceof io.github.izakyl.folkways.core.facility.api.Contribution.Workshop work) {
                                offers.add(work.workshop());
                            }
                        }));
            }
            colony.publish(owner, view.dimension(), offers);
            present.add(view.dimension());
        }
        dimensions.stream().filter(dimension -> !present.contains(dimension)).forEach(this::clear);
        dimensions.clear();
        dimensions.addAll(present);
    }

    private void visitPorts(ColonyView view, BiConsumer<WorldPos, P> visitor) {
        var level = view.level();
        for (var at : colony.storesIn(view.dimension())) {
            var pos = at.block(level);
            if (level.isLoaded(pos) && !StoreAccess.unavailable(level, pos)) {
                locate.apply(level, pos).ifPresent(port -> visitor.accept(at, port));
            }
        }
    }

    public void close() {
        dimensions.forEach(this::clear);
        dimensions.clear();
    }

    private void clear(ResourceKey<Level> dimension) {
        colony.publish(owner, dimension, List.of());
        colony.rule(owner, dimension, List.of());
    }
}
