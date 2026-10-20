package io.github.izakyl.folkways.plugins.compost;

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
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A native delegation, with no optional mod or equipment prerequisite. */
public final class CompostPresence implements Facing, Holding.Watch {
    private final Colony colony;
    private final MinecraftServer server;
    private final SeedSupport support;
    private final CompostBookings bookings = new CompostBookings();
    private final CompostStation station;
    private final Sweep sweep;
    private final Set<WorldPos> registered = new LinkedHashSet<>();
    private SeedBudget discovery;
    private boolean discovering;
    private final java.util.Map<net.minecraft.world.item.Item, Long> offered = new java.util.LinkedHashMap<>();

    public CompostPresence(Colony colony, MinecraftServer server) {
        this.colony = colony; this.server = server;
        support = new SeedSupport(colony, server);
        sweep = new Sweep(colony, CompostContent.ID, 40);
        station = new CompostStation(ignored -> Optional.of(discovering ? discovery : support.budget(server.overworld())), support::catalogue,
            (item, count) -> {
                int capacity = support.batchLimit(item, count);
                if (!discovering) return capacity;
                int allowed = (int) Math.max(0, Math.min(capacity,
                    discovery.tally(item).compostable() - offered.getOrDefault(item, 0L)));
                offered.merge(item, (long) allowed, Long::sum);
                return allowed;
            });
    }

    public CompostStation.Stats stats() { return station.stats(); }
    public List<UUID> activeSessions() { return station.activeSessions(); }
    public SeedBudget budget(ServerLevel level) { return support.budget(level); }

    public void held(Holding holding) {
        holding.what().cells().forEach(at -> { registered.add(at); bookings.admit(at); });
        sweep.nudge();
    }
    public void released(Holding holding, Release why) {
        holding.what().cells().forEach(at -> {
            registered.remove(at); bookings.withdraw(at);
            at.level(server).ifPresent(level -> station.release(colony.id(), level, at));
        });
        sweep.nudge();
    }
    public void tick(MinecraftServer server) {
        discovery = null;
        offered.clear();
        sweep.tick(server, this::refresh, this::goals);
        discovery = null;
    }
    private void refresh(MinecraftServer server) {
        Set<WorldPos> marked = new LinkedHashSet<>();
        colony.holdings(CompostContent.ID).forEach(holding -> marked.addAll(holding.what().cells()));
        for (WorldPos at : Set.copyOf(registered)) if (!marked.contains(at)) {
            bookings.withdraw(at);
            at.level(server).ifPresent(level -> station.release(colony.id(), level, at));
        }
        registered.clear(); registered.addAll(marked);
        registered.forEach(bookings::admit);
    }
    public List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        List<Grown> goals = new ArrayList<>();
        // Every barrel and seed candidate in this sweep shares the same immutable observation.
        if (discovery == null) discovery = support.budget(level).frozen();
        discovering = true;
        try {
            for (WorldPos at : registered) {
                if (!at.in(level) || !level.isLoaded(at.block(level))) continue;
                if (!station.matches(level.getBlockState(at.block(level)))) {
                    bookings.withdraw(at); station.release(colony.id(), level, at); continue;
                }
                bookings.admit(at);
                Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, at.block(level)));
                if (stances.isPresent()) goals.addAll(station.recovery(colony.id(), level, at, stances.get(), bookings, sweep::nudge));
                else goals.addAll(station.activeRecovery(colony.id(), level, at, bookings));
            }
        } finally { discovering = false; }
        return List.copyOf(goals);
    }
    public void closed(Closing why) {
        station.stop(colony.id(), why); bookings.clear(); registered.clear(); discovery = null; offered.clear(); discovering = false;
    }
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(CompostContent.ID)) return Optional.empty();
        List<Board.Row> rows = new ArrayList<>();
        ServerLevel level = view.level();
        for (WorldPos at : registered) if (at.in(level) && level.isLoaded(at.block(level))) {
            rows.add(new Board.Row(new ItemStack(Items.COMPOSTER), Component.translatable("block.minecraft.composter"),
                station.status(level, at), List.of(new Board.Act.Ping(at.block(level)))));
        }
        return Optional.of(new Board(List.of(new Board.Figure("folkways.page.compost.count",
            Component.literal(Integer.toString(rows.size())))), rows, Optional.of(Component.translatable("folkways.page.compost.empty"))));
    }
}
