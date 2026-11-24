package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.StandGeometry;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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

/** Owns native composters independently of any optional cooking integration. */
public final class CompostPresence implements HoldingKind, Claim.Watch {
    private final Colony colony;
    private final MinecraftServer server;
    private final SeedSupport seeds;
    private final Sweep sweep;
    private final Runnable nudge;
    private final CompostStation station;
    private final CompostBookings bookings = new CompostBookings();
    private final Map<WorldPos, Optional<Stances>> sites = new LinkedHashMap<>();
    private final Map<WorldPos, CompostPrefab> prefabs = new LinkedHashMap<>();
    private boolean dirty = true;

    public CompostPresence(Colony colony, MinecraftServer server, SeedSupport seeds) {
        this.colony = colony;
        this.server = server;
        this.seeds = seeds;
        sweep = new Sweep(colony, CompostContent.ID, 40);
        nudge = sweep::nudge;
        station = new CompostStation(id -> id.equals(colony.id())
            ? Optional.of(seeds.budget(server.overworld())) : Optional.empty(), seeds::catalogue,
            id -> id.equals(colony.id()) && CompostSettings.enabled(colony));
    }

    public CompostStation station() { return station; }

    public SeedBudget budget(ServerLevel level) { return seeds.budget(level); }

    public void held(Claim holding) { dirty = true; sweep.nudge(); }

    public void released(Claim holding, ClaimRelease why) {
        for (WorldPos at : holding.what().cells()) {
            bookings.withdraw(at);
            sites.remove(at);
            prefabs.remove(at);
            at.level(server).ifPresent(level -> station.release(colony.id(), level, at));
        }
        dirty = true;
        sweep.nudge();
    }

    public void tick(MinecraftServer server) {
        if (dirty) { refresh(server); sweep.nudge(); }
        sweep.tick(server, this::refresh, this::goals);
    }

    void coverageChanged(MinecraftServer server) {
        refresh(server);
        sweep.nudge();
    }

    public void refresh(MinecraftServer server) {
        dirty = false;
        Set<WorldPos> marked = new LinkedHashSet<>();
        colony.claims(CompostContent.POINT).forEach(holding -> marked.addAll(holding.what().cells()));
        for (WorldPos at : List.copyOf(sites.keySet())) {
            if (!marked.contains(at)) {
                bookings.withdraw(at);
                at.level(server).ifPresent(level -> station.release(colony.id(), level, at));
                sites.remove(at);
            }
        }
        prefabs.clear();
        for (WorldPos at : marked) {
            at.level(server).filter(level -> at.in(level) && level.isLoaded(at.block(level))).ifPresent(level -> {
                Optional<CompostPrefab> prefab = CompostPrefab.at(level, at.block(level));
                if (prefab.isEmpty()) {
                    bookings.withdraw(at);
                    station.release(colony.id(), level, at);
                    sites.remove(at);
                } else {
                    bookings.admit(at);
                    sites.put(at, approach(level, at));
                    prefabs.put(at, prefab.get().offering(colony.id(), station, bookings, nudge));
                }
            });
        }
        for (ServerLevel level : server.getAllLevels()) {
            List<Workshop> workshops = new ArrayList<>();
            prefabs.forEach((at, prefab) -> {
                if (at.in(level)) {
                    for (Contribution contribution : prefab.projections(level, Map.of())) {
                        if (contribution instanceof Contribution.Workshop work) {
                            workshops.add(work.workshop());
                        }
                    }
                }
            });
            colony.publish(CompostContent.ID, level.dimension(), workshops);
        }
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        ServerLevel level = view.level();
        CompostStation.Planning planning = station.planning(colony.id());
        prefabs.forEach((at, prefab) -> {
            if (at.in(level) && CompostPrefab.at(level, at.block(level)).isPresent()) {
                var stances = approach(level, at);
                sites.put(at, stances);
                goals.addAll(prefab.workshop().orElseThrow().goals(level, stances, planning));
            }
        });
        return List.copyOf(goals);
    }

    private static Optional<Stances> approach(ServerLevel level, WorldPos at) {
        return Stances.of(StandGeometry.footingsOf(level, at).stream().map(Stand::cell).toList());
    }

    public void closed(Closing why) {
        for (ServerLevel level : server.getAllLevels()) {
            colony.publish(CompostContent.ID, level.dimension(), List.of());
        }
        station.stop(colony.id(), why);
        sites.clear();
        prefabs.clear();
        bookings.clear();
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        BlockPos cell = held.site().block(view.level());
        WorldPos at = WorldPos.of(view.level(), cell);
        if (!sites.containsKey(at)) { return List.of(); }
        Component status = sites.get(at).isEmpty()
            ? Component.translatable("folkways.compost.status.unreachable") : station.status(view.level(), at);
        return List.of(Line.text(status));
    }
}
