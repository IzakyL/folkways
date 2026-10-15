package io.github.izakyl.folkways.plugins.cookery;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class CookeryPresence implements Facing, Holding.Watch {
    private final Colony colony;
    private final MinecraftServer server;
    private final Sweep sweep;
    private final io.github.izakyl.folkways.plugins.cookery.tools.TaskTools tools;
    private final KitchenBookings bookings = new KitchenBookings();
    private final Map<WorldPos, Station> stations = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> published = new LinkedHashSet<>();
    private boolean refreshPending;

    public CookeryPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        this.tools = new io.github.izakyl.folkways.plugins.cookery.tools.TaskTools(colony, server);
        this.sweep = new Sweep(colony, CookeryContent.ID, 40);
    }

    @Override
    public void held(Holding holding) {
        for (WorldPos at : holding.what().cells()) {
            bookings.admit(at);
            at.level(server).ifPresent(level -> stations.putIfAbsent(at,
                new Station(level, UUID.randomUUID(), Optional.empty(), KitchenStations.find(level.getBlockState(at.block(level))).orElse(null))));
        }
        refreshPending = true;
    }

    @Override
    public void released(Holding holding, Release why) {
        for (WorldPos at : holding.what().cells()) {
            bookings.withdraw(at);
            Station station = stations.remove(at);
            ServerLevel level = station == null ? at.level(server).orElse(null) : station.level();
            if (level != null) {
                release(level, at);
            }
        }
        refreshPending = true;
        sweep.nudge();
    }

    public io.github.izakyl.folkways.plugins.cookery.tools.TaskTools tools() { return tools; }

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
        Set<WorldPos> marked = new LinkedHashSet<>();
        CookeryContent.stations().forEach(owner -> colony.holdings(owner).forEach(holding -> marked.addAll(holding.what().cells())));
        stations.entrySet().removeIf(entry -> {
            if (!marked.contains(entry.getKey())) {
                bookings.withdraw(entry.getKey());
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
                    continue;
                }
                WorldPos at = WorldPos.of(level, cell);
                if (!CookeryContent.station(level.getBlockState(cell))) {
                    bookings.withdraw(at);
                    stations.remove(at);
                    continue;
                }
                bookings.admit(at);
                KitchenStation provider = KitchenStations.find(level.getBlockState(cell)).orElseThrow();
                Optional<Stances> stances = provider.stances(level, at);
                UUID key = stations.containsKey(at) ? stations.get(at).key() : UUID.randomUUID();
                stations.put(at, new Station(level, key, stances, provider));
                if (stances.isPresent()) {
                    provider.freeze(key, colony.id(), level, at, stances.get(), bookings, sweep::nudge)
                        .ifPresent(workshops::add);
                }
            }
            colony.publish(CookeryContent.ID, level.dimension(), workshops);
            colony.rule(CookeryContent.ID, level.dimension(),
                io.github.izakyl.folkways.plugins.cookery.racks.KitchenRacks.rules(
                    colony.storesIn(level.dimension()).stream().filter(at -> level.isLoaded(at.block(level))
                        && io.github.izakyl.folkways.plugins.cookery.racks.KitchenRacks.matches(
                            level.getBlockState(at.block(level)))).toList()));
        }
        for (ResourceKey<Level> dimension : published) {
            if (!dimensions.contains(dimension)) {
                colony.publish(CookeryContent.ID, dimension, List.of());
                colony.rule(CookeryContent.ID, dimension, List.of());
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
                if (station.stances().isPresent()) {
                    goals.addAll(station.provider().recovery(colony.id(), level, at, station.stances().get(), bookings, sweep::nudge));
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
        stations.clear();
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
        return colony.service(io.github.izakyl.folkways.plugins.cookery.seeds.SeedPresence.ID,
            io.github.izakyl.folkways.plugins.cookery.seeds.SeedPresence.class)
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

    private record Station(ServerLevel level, UUID key, Optional<Stances> stances, KitchenStation provider) {
    }
}
