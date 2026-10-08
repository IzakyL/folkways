package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.Fronts;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

final class PatrolPresence {

    static final ResourceLocation ENGAGE = ResourceLocation.fromNamespaceAndPath("folkways", "engage");

    private static final int PERIOD = 100;

    private static final double ENGAGE_RANGE = 12.0;

    // Above fear, so a patroller struck by what it is fighting fights on rather than running.
    private static final double STAND_GROUND = Urge.DIRE + 0.5;

    // Below this share of its health a patroller no longer stands its ground, and fear can take it.
    private static final float WOUNDED = 0.35F;

    // One walk of a route by one patroller, stop after stop, with the stops not yet ended. Laps go out and back by
    // turns; a lap is walked until every stop of it has ended.
    private record Lap(int number, Grown round, Set<UUID> open) {

        Lap(int number, Grown round) {
            this(number, round, Set.copyOf(round.completions()));
        }

        boolean walking() {
            return !open.isEmpty();
        }

        Lap without(UUID stop) {
            Set<UUID> left = new LinkedHashSet<>(open);
            left.remove(stop);
            return new Lap(number, round, Set.copyOf(left));
        }
    }

    private final Colony colony;

    private final Asking asking;

    private final AtomicBoolean offerPending = new AtomicBoolean();

    private int sinceSweep = PERIOD;

    private final Map<UUID, Long> lastWatch = new ConcurrentHashMap<>();

    private final Map<UUID, Lap> laps = new ConcurrentHashMap<>();

    private volatile Map<UUID, List<Stop>> routes = Map.of();

    PatrolPresence(Colony colony) {
        this.colony = colony;
        this.asking = new Asking(colony, PatrolContent.ID, this::ended);
    }

    public void tick(MinecraftServer server) {
        if (++sinceSweep >= PERIOD) {
            sinceSweep = 0;
            refresh(server);
            offerPending.set(true);
        }
        if (offerPending.getAndSet(false)) {
            asking.offer(server, this::goals);
        }
    }

    void refresh(MinecraftServer server) {
        Map<UUID, List<Stop>> found = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) {
            found.putAll(Beats.charted(Fronts.of(colony), view.level()));
        }
        routes = Map.copyOf(found);
        laps.keySet().retainAll(found.keySet());
        long now = server.overworld().getGameTime();
        lastWatch.values().removeIf(at -> now - at > FolkwaysConfig.patrolWardTicks());
    }

    // A lap under way is offered as it was set out; a new one sets out once any stop on the route
    // has half its ward run, so the route is walked again before a ward on it lapses.
    public List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        long now = level.getGameTime();
        long due = FolkwaysConfig.patrolWardTicks() / 2;
        List<Grown> rounds = new ArrayList<>();
        routes.forEach((route, stops) -> {
            if (!stops.getFirst().at().in(level)) {
                return;
            }
            Lap lap = laps.get(route);
            if (lap != null && lap.walking()) {
                rounds.add(lap.round().remaining(lap.open()));
                return;
            }
            boolean wanted = stops.stream()
                .anyMatch(stop -> Wards.until(level, stop.at().block(level)) <= now + due);
            if (wanted) {
                int number = lap == null ? 0 : lap.number() + 1;
                Lap next = new Lap(number, round(route, number, stops));
                laps.put(route, next);
                rounds.add(next.round());
            }
        });
        return rounds;
    }

    Grown round(UUID route, int number, List<Stop> stops) {
        List<Stop> ordered = new ArrayList<>(stops);
        if (number % 2 == 1) {
            Collections.reverse(ordered);
        }
        List<Node> watches = new ArrayList<>(ordered.size());
        List<Before> order = new ArrayList<>();
        for (int at = 0; at < ordered.size(); at++) {
            watches.add(new WatchNode(idOf(route, number, at), ordered.get(at), this));
            if (at > 0) {
                order.add(new Before(watches.get(at - 1).id(), watches.get(at).id()));
            }
        }
        return new Grown(watches, order).byOneWorker();
    }

    private void ended(UUID node, Ending how) {
        laps.replaceAll((route, lap) -> lap.open().contains(node) ? lap.without(node) : lap);
        offerPending.set(true);
    }

    void watched(UUID resident, long at) {
        lastWatch.put(resident, at);
    }

    // A patroller on duty - one who stood watch while its last ward still holds - goes for monsters near it.
    public List<Urge> urges(Worker who, ColonyView view) {
        Mob body = who.body();
        if (!(body.level() instanceof ServerLevel level) || !onDuty(who.resident().id(), level.getGameTime())
                || !armed(who)) {
            return List.of();
        }
        Optional<Mob> foe = nearestFoe(level, body);
        if (foe.isEmpty()) {
            return List.of();
        }
        Optional<Stances> reach = Stances.of(level, Reach.workableCells(level, foe.get().blockPosition()));
        if (reach.isEmpty()) {
            return List.of();
        }
        double weight = body.getHealth() > body.getMaxHealth() * WOUNDED ? STAND_GROUND : Urge.NOW;
        Mob target = foe.get();
        Stances stances = reach.get();
        return List.of(new Urge(ENGAGE, weight, true, () -> new StrikeNode(level, target, stances)));
    }

    private boolean onDuty(UUID resident, long now) {
        Long at = lastWatch.get(resident);
        return at != null && now - at <= FolkwaysConfig.patrolWardTicks();
    }

    private static boolean armed(Worker who) {
        return ResidentKinds.barehanded(who.resident().kind(), Optional.of(PatrolContent.trade()))
            || !who.held(PatrolContent.WEAPON).isEmpty();
    }

    private static Optional<Mob> nearestFoe(ServerLevel level, Mob body) {
        List<Mob> near = level.getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(ENGAGE_RANGE),
            mob -> mob instanceof Enemy && mob.isAlive() && !mob.isRemoved());
        near.sort(Comparator.comparingDouble(body::distanceToSqr));
        for (Mob mob : near) {
            if (body.hasLineOfSight(mob)) {
                return Optional.of(mob);
            }
        }
        return Optional.empty();
    }

    private static UUID idOf(UUID route, int lap, int stop) {
        return UUID.nameUUIDFromBytes(("folkways:patrol/" + route + "/" + lap + "/" + stop)
            .getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        routes = Map.of();
        laps.clear();
        lastWatch.clear();
    }
}
