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
import io.github.izakyl.folkways.front.api.notice.Line;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Owns native composters independently of any optional cooking integration. */
public final class CompostPresence implements Facing, Holding.Watch {
    private final Colony colony;
    private final MinecraftServer server;
    private final SeedSupport seeds;
    private final Sweep sweep;
    private final CompostStation station;
    private final CompostBookings bookings = new CompostBookings();
    private final Map<WorldPos, Optional<Stances>> sites = new LinkedHashMap<>();
    private boolean dirty = true;

    public CompostPresence(Colony colony, MinecraftServer server, SeedSupport seeds) {
        this.colony = colony;
        this.server = server;
        this.seeds = seeds;
        sweep = new Sweep(colony, CompostContent.ID, 40);
        station = new CompostStation(id -> id.equals(colony.id())
            ? Optional.of(seeds.budget(server.overworld())) : Optional.empty(), seeds::catalogue,
            id -> id.equals(colony.id()) && CompostSettings.enabled(colony));
    }

    public CompostStation station() { return station; }
    public SeedBudget budget(ServerLevel level) { return seeds.budget(level); }

    public void held(Holding holding) { dirty = true; sweep.nudge(); }

    public void released(Holding holding, Release why) {
        for (WorldPos at : holding.what().cells()) {
            bookings.withdraw(at);
            sites.remove(at);
            at.level(server).ifPresent(level -> station.release(colony.id(), level, at));
        }
        dirty = true;
        sweep.nudge();
    }

    public void tick(MinecraftServer server) {
        if (dirty) { refresh(server); sweep.nudge(); }
        sweep.tick(server, this::refresh, this::goals);
    }

    public void refresh(MinecraftServer server) {
        dirty = false;
        Set<WorldPos> marked = new LinkedHashSet<>();
        colony.holdings(CompostContent.POINT).forEach(holding -> marked.addAll(holding.what().cells()));
        for (WorldPos at : List.copyOf(sites.keySet())) {
            if (!marked.contains(at)) {
                bookings.withdraw(at);
                at.level(server).ifPresent(level -> station.release(colony.id(), level, at));
                sites.remove(at);
            }
        }
        for (WorldPos at : marked) {
            at.level(server).filter(level -> at.in(level) && level.isLoaded(at.block(level))).ifPresent(level -> {
                if (!level.getBlockState(at.block(level)).is(Blocks.COMPOSTER)) {
                    bookings.withdraw(at);
                    station.release(colony.id(), level, at);
                    sites.remove(at);
                } else {
                    bookings.admit(at);
                    sites.put(at, Stances.of(level, Reach.workableCells(level, at.block(level))));
                }
            });
        }
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        ServerLevel level = view.level();
        CompostStation.Planning planning = station.planning(colony.id());
        sites.forEach((at, stances) -> {
            if (!at.in(level) || !level.isLoaded(at.block(level))) return;
            goals.addAll(stances.isPresent()
                ? station.recovery(colony.id(), level, at, stances.get(), bookings, sweep::nudge, planning)
                : station.activeRecovery(colony.id(), level, at, bookings));
        });
        return List.copyOf(goals);
    }

    public void closed(Closing why) {
        station.stop(colony.id(), why);
        sites.clear();
        bookings.clear();
    }

    public List<Line> blockLines(BlockPos cell, ColonyView view) {
        WorldPos at = WorldPos.of(view.level(), cell);
        if (!sites.containsKey(at)) return List.of();
        Component status = sites.get(at).isEmpty()
            ? Component.translatable("folkways.compost.status.unreachable") : station.status(view.level(), at);
        return List.of(Line.text(status));
    }
}
