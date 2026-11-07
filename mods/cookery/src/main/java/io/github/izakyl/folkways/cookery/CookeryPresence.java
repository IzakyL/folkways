package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class CookeryPresence implements Facing, Claim.Watch {
    private final Colony colony;
    private final MinecraftServer server;
    private final Sweep sweep;
    private final io.github.izakyl.folkways.cookery.tools.TaskTools tools;
    private final KitchenBookings bookings = new KitchenBookings();
    private final Map<WorldPos, Station> stations = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> published = new LinkedHashSet<>();
    private final Map<ResourceKey<Level>, Set<WorldPos>> racks = new LinkedHashMap<>();
    private InventorySnapshot.Version rackVersion;
    private boolean refreshPending;

    public CookeryPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        this.tools = new io.github.izakyl.folkways.cookery.tools.TaskTools(colony, server);
        this.sweep = new Sweep(colony, CookeryContent.ID, 40);
    }

    @Override
    public void held(Claim holding) {
        for (WorldPos at : holding.what().cells()) {
            bookings.admit(at);
            at.level(server).ifPresent(level -> stations.putIfAbsent(at,
                new Station(level, UUID.randomUUID(), Optional.empty(), null, null)));
        }
        refreshPending = true;
    }

    @Override
    public void released(Claim holding, ClaimRelease why) {
        for (WorldPos at : holding.what().cells()) {
            bookings.withdraw(at);
            Station station = stations.remove(at);
            if (station != null && station.approach() != null) { station.approach().close(); }
            ServerLevel level = station == null ? at.level(server).orElse(null) : station.level();
            if (level != null) {
                release(level, at);
            }
        }
        refreshPending = true;
        sweep.nudge();
    }

    public io.github.izakyl.folkways.cookery.tools.TaskTools tools() { return tools; }

    public void tick(MinecraftServer server) {
        tools.tick(server);
        for (ServerLevel level : server.getAllLevels()) {
            KitchenStations.all().forEach(station -> station.tick(level, colony.id()));
        }
        if (refreshPending) {
            refresh(server);
            sweep.nudge();
        }
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        refreshPending = false;
        InventorySnapshot inventory = colony.inventory().snapshot();
        if (inventory.coverage().membershipPending()) {
            colony.inventory().ensureObserved();
        }
        boolean updateRacks = !inventory.coverage().membershipPending() && (rackVersion == null
            || !rackVersion.epoch().equals(inventory.version().epoch())
            || rackVersion.topology() != inventory.version().topology()
            || rackVersion.coverage() != inventory.version().coverage());
        Set<WorldPos> marked = new LinkedHashSet<>();
        CookeryContent.stations().forEach(owner -> colony.claims(owner).forEach(holding -> marked.addAll(holding.what().cells())));
        stations.entrySet().removeIf(entry -> {
            if (!marked.contains(entry.getKey())) {
                bookings.withdraw(entry.getKey());
                if (entry.getValue().approach() != null) { entry.getValue().approach().close(); }
                release(entry.getValue().level(), entry.getKey());
                return true;
            }
            return false;
        });
        Set<ResourceKey<Level>> dimensions = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            dimensions.add(level.dimension());
            List<Workshop> workshops = new ArrayList<>();
            Set<BlockPos> cells = new LinkedHashSet<>();
            CookeryContent.stations().forEach(owner -> cells.addAll(view.blocks(owner)));
            for (BlockPos cell : cells) {
                if (!level.isLoaded(cell)) {
                    Station previous = stations.get(WorldPos.of(level, cell));
                    if (previous != null && previous.approach() != null) {
                        previous.approach().close();
                        stations.put(WorldPos.of(level, cell),
                            new Station(level, previous.key(), Optional.empty(), null, null));
                    }
                    continue;
                }
                WorldPos at = WorldPos.of(level, cell);
                if (!CookeryContent.station(level.getBlockState(cell))) {
                    bookings.withdraw(at);
                    Station removed = stations.remove(at);
                    if (removed != null && removed.approach() != null) { removed.approach().close(); }
                    continue;
                }
                bookings.admit(at);
                KitchenStation provider = KitchenStations.find(level.getBlockState(cell)).orElseThrow();
                Station previous = stations.get(at);
                UUID key = previous == null ? UUID.randomUUID() : previous.key();
                StationApproach approach = previous == null ? null : previous.approach();
                if (previous == null || previous.provider() != provider || previous.level() != level) {
                    if (approach != null) { approach.close(); }
                    approach = new StationApproach(level, at, provider);
                }
                Optional<Stances> stances = approach.get();
                stations.put(at, new Station(level, key, stances, provider, approach));
                if (stances.isPresent()) {
                    approach.freeze(() -> provider.freeze(key, colony.id(), level, at, stances.get(), bookings,
                        sweep::nudge))
                        .ifPresent(workshops::add);
                }
            }
            colony.publish(CookeryContent.ID, level.dimension(), workshops);
            if (!inventory.coverage().membershipPending() && (updateRacks || !racks.containsKey(level.dimension()))) {
                Set<WorldPos> previous = racks.getOrDefault(level.dimension(), Set.of());
                Set<WorldPos> found = new LinkedHashSet<>();
                for (WorldPos at : colony.storesIn(level.dimension())) {
                    var store = inventory.store(at).orElse(null);
                    if (store == null || store.state() != InventorySnapshot.State.OBSERVED) {
                        colony.inventory().ensureObserved(Stash.at(at));
                        if (previous.contains(at)) { found.add(at); }
                    } else if (store.block().isEmpty()) {
                        if (previous.contains(at)) { found.add(at); }
                    } else if (store.block().get().equals(BuiltInRegistries.BLOCK.getKey(
                            com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.KITCHENWARE_RACKS.get()))) {
                        found.add(at);
                    }
                }
                racks.put(level.dimension(), found);
                colony.rule(CookeryContent.ID, level.dimension(),
                    io.github.izakyl.folkways.cookery.racks.KitchenRacks.rules(found));
            }
        }
        if (updateRacks) { rackVersion = inventory.version(); }
        for (ResourceKey<Level> dimension : published) {
            if (!dimensions.contains(dimension)) {
                colony.publish(CookeryContent.ID, dimension, List.of());
                colony.rule(CookeryContent.ID, dimension, List.of());
                racks.remove(dimension);
            }
        }
        published.clear();
        published.addAll(dimensions);
    }

    public List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        List<Grown> goals = new ArrayList<>(tools.goals(view));
        stations.forEach((at, station) -> {
            if (at.in(level) && level.isLoaded(at.block(level)) && station.provider() != null) {
                Optional<Stances> current = station.approach().get();
                if (current.isPresent()) {
                    goals.addAll(station.approach().observe(() -> station.provider().recovery(colony.id(), level, at,
                        current.get(), bookings, sweep::nudge)));
                } else {
                    goals.addAll(station.provider().activeRecovery(colony.id(), level, at, bookings));
                }
            }
        });
        return List.copyOf(goals);
    }

    private void release(ServerLevel level, WorldPos at) {
        if (at.in(level) && level.isLoaded(at.block(level))) {
            KitchenStations.all().forEach(provider -> provider.release(colony.id(), level, at));
        }
    }

    public void closed(Closing why) {
        tools.closed(why);
        KitchenStations.all().forEach(provider -> provider.stop(colony.id(), why));
        if (why != Closing.SHUTDOWN) {
            stations.forEach((at, station) -> {
                bookings.withdraw(at);
                release(station.level(), at);
            });
        }
        stations.values().forEach(station -> {
            if (station.approach() != null) { station.approach().close(); }
        });
        stations.clear();
        racks.clear();
        rackVersion = null;
        published.clear();
        bookings.clear();
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(CookeryContent.ID)) {
            return Optional.empty();
        }
        ServerLevel level = view.level();
        List<Board.Row> rows = new ArrayList<>();
        for (Map.Entry<WorldPos, Station> entry : stations.entrySet()) {
            WorldPos at = entry.getKey();
            if (!at.in(level) || !level.isLoaded(at.block(level))) {
                continue;
            }
            BlockPos cell = at.block(level);
            rows.add(new Board.Row(new ItemStack(level.getBlockState(cell).getBlock()),
                level.getBlockState(cell).getBlock().getName(), state(level, cell, entry.getValue()),
                List.of(new Board.Act.Ping(cell))));
        }
        int stationsHere = rows.size();
        rows.add(new Board.Row(com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems.STRAW_HAT.get().getDefaultInstance(),
            Component.translatable("folkways.settings.cookery.wear_hat"), Component.empty(),
            List.of(new Board.Act.Edit("wear_hat"))));
        rows.add(new Board.Row(com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems.FARMER_CHEST_PLATE.get().getDefaultInstance(),
            Component.translatable("folkways.settings.cookery.wear_farmer_armor"), Component.empty(),
            List.of(new Board.Act.Edit("wear_farmer_armor"))));
        rows.add(new Board.Row(com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems.STRAW_HAT.get().getDefaultInstance(),
            Component.translatable("folkways.settings.cookery.clothing"), Component.empty(),
            List.of(new Board.Act.Edit("clothing"))));
        return Optional.of(new Board(List.of(new Board.Figure("folkways.page.cookery.stations",
            Component.literal(Integer.toString(stationsHere)))), rows,
            Optional.of(Component.translatable("folkways.page.cookery.empty"))));
    }

    @Override
    public List<Line> zoneLines(io.github.izakyl.folkways.front.api.ZoneView zone, ColonyView view) {
        return colony.service(io.github.izakyl.folkways.cookery.seeds.SeedPresence.ID,
            io.github.izakyl.folkways.cookery.seeds.SeedPresence.class)
            .map(seeds -> seeds.zoneLines(zone, view)).orElseGet(List::of);
    }

    @Override
    public List<Line> blockLines(BlockPos at, ColonyView view) {
        Station station = stations.get(WorldPos.of(view.level(), at));
        return station == null ? List.of() : List.of(Line.text(state(view.level(), at, station)));
    }

    private static Component state(ServerLevel level, BlockPos cell, Station station) {
        if (station.stances().isEmpty()) {
            return Component.translatable("folkways.page.cookery.unreachable");
        }
        return station.provider() == null ? Component.translatable("folkways.page.cookery.idle")
            : station.provider().status(level, WorldPos.of(level, cell));
    }

    private record Station(ServerLevel level, UUID key, Optional<Stances> stances, KitchenStation provider,
                           StationApproach approach) {
    }
}
