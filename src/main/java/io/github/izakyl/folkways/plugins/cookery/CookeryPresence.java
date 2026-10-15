package io.github.izakyl.folkways.plugins.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.plugins.cookery.stockpot.StockpotWorkshops;
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
    private final KitchenBookings bookings = new KitchenBookings();
    private final Map<WorldPos, Station> stations = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> published = new LinkedHashSet<>();
    private boolean refreshPending;

    public CookeryPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        this.sweep = new Sweep(colony, CookeryContent.ID, 40);
    }

    @Override
    public void held(Holding holding) {
        for (WorldPos at : holding.what().cells()) {
            bookings.admit(at);
            at.level(server).ifPresent(level -> stations.putIfAbsent(at,
                new Station(level, UUID.randomUUID(), Optional.empty())));
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

    public void tick(MinecraftServer server) {
        if (refreshPending) {
            refresh(server);
            sweep.nudge();
        }
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        refreshPending = false;
        Set<WorldPos> marked = new LinkedHashSet<>();
        colony.holdings(CookeryContent.STATION).forEach(holding -> marked.addAll(holding.what().cells()));
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
            for (BlockPos cell : view.blocks(CookeryContent.STATION)) {
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
                Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, cell));
                UUID key = stations.containsKey(at) ? stations.get(at).key() : UUID.randomUUID();
                stations.put(at, new Station(level, key, stances));
                if (stances.isPresent()) {
                    ChoppingBoardWorkshops.freeze(key, colony.id(), level, at, stances.get(), bookings, sweep::nudge)
                        .ifPresent(workshops::add);
                    StockpotWorkshops.freeze(key, colony.id(), level, at, stances.get(), bookings, sweep::nudge)
                        .ifPresent(workshops::add);
                }
            }
            colony.publish(CookeryContent.ID, level.dimension(), workshops);
        }
        for (ResourceKey<Level> dimension : published) {
            if (!dimensions.contains(dimension)) {
                colony.publish(CookeryContent.ID, dimension, List.of());
            }
        }
        published.clear();
        published.addAll(dimensions);
    }

    public List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        List<Grown> goals = new ArrayList<>();
        stations.forEach((at, station) -> {
            if (at.in(level) && level.isLoaded(at.block(level)) && station.stances().isPresent()) {
                Stances stances = station.stances().get();
                goals.addAll(ChoppingBoardWorkshops.recovery(colony.id(), level, at, stances, bookings, sweep::nudge));
                goals.addAll(StockpotWorkshops.recovery(colony.id(), level, at, stances, bookings, sweep::nudge));
            }
        });
        return List.copyOf(goals);
    }

    private void release(ServerLevel level, WorldPos at) {
        if (at.in(level) && level.isLoaded(at.block(level))) {
            ChoppingBoardWorkshops.release(colony.id(), level, at);
            StockpotWorkshops.release(colony.id(), level, at);
        }
    }

    public void closed(Closing why) {
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
        return Optional.of(new Board(List.of(new Board.Figure("folkways.page.cookery.stations",
            Component.literal(Integer.toString(rows.size())))), rows,
            Optional.of(Component.translatable("folkways.page.cookery.empty"))));
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
        String status = "idle";
        if (level.getBlockEntity(cell) instanceof ChoppingBoardBlockEntity board) {
            status = board.getCurrentCutStack().isEmpty() ? "idle" : "chopping";
        } else if (level.getBlockEntity(cell) instanceof StockpotBlockEntity pot) {
            status = switch (pot.getStatus()) {
                case IStockpot.COOKING -> "cooking";
                case IStockpot.FINISHED -> "finished";
                case IStockpot.PUT_INGREDIENT -> "preparing";
                default -> "idle";
            };
            if (pot.getStatus() != IStockpot.FINISHED && !pot.hasHeatSource(level)) {
                status = "cold";
            }
        }
        return Component.translatable("folkways.page.cookery." + status);
    }

    private record Station(ServerLevel level, UUID key, Optional<Stances> stances) {
    }
}
