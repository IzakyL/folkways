package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PathView;
import io.github.izakyl.folkways.front.api.panel.Board;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class PatrolPresence implements Facing {

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

    // When routes are read again within each period, spread against every other cron; see Cadence.
    private final Cadence.Cron cron;
    private boolean swept;

    private final Map<UUID, Long> lastWatch = new ConcurrentHashMap<>();

    private final Map<UUID, Long> dutyUntil = new ConcurrentHashMap<>();

    private final Map<UUID, Lap> laps = new ConcurrentHashMap<>();

    private final Map<UUID, Long> alerts = new ConcurrentHashMap<>();

    private volatile Map<UUID, List<Stop>> routes = Map.of();

    PatrolPresence(Colony colony) {
        this.colony = colony;
        this.asking = new Asking(colony, PatrolContent.ID, this::ended);
        this.cron = Cadence.claim(colony, PERIOD);
    }

    public void tick(MinecraftServer server) {
        if (!swept || cron.due(server)) {
            swept = true;
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
        lastWatch.keySet().removeIf(id -> dutyUntil.getOrDefault(id, Long.MIN_VALUE) < now);
        dutyUntil.values().removeIf(until -> until < now);
    }

    // A lap under way is offered as it was set out; a new one sets out once any stop on the route
    // has half its ward run, so the route is walked again before a ward on it lapses.
    public List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        long now = level.getGameTime();
        long due = FolkwaysVanillaConfig.patrolWardTicks() / 2;
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

    // Each route by how many of its stops hold a ward now; one with nowhere to stand along it has no stops at all.
    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(PatrolContent.ID)) {
            return Optional.empty();
        }
        ServerLevel level = view.level();
        long now = level.getGameTime();
        Map<UUID, List<Stop>> charted = routes;
        int count = 0;
        int held = 0;
        List<Board.Row> rows = new ArrayList<>();
        for (PathView path : Fronts.of(colony).paths()) {
            if (!path.delegation().equals(PatrolContent.BEAT) || !path.dimension().equals(level.dimension())
                    || path.points().isEmpty()) {
                continue;
            }
            count++;
            ItemStack icon = new ItemStack(Items.IRON_SWORD);
            Component title = Component.translatable("folkways.delegation.folkways.patrol");
            List<Stop> stops = charted.getOrDefault(path.id(), List.of());
            if (stops.isEmpty()) {
                rows.add(new Board.Row(icon, title, Component.translatable("folkways.page.patrol.unwalkable"),
                    List.of(new Board.Act.Ping(path.points().getFirst()))));
                continue;
            }
            int warded = (int) stops.stream().filter(stop -> Wards.until(level, stop.at().block(level)) > now).count();
            held += warded;
            Lap lap = laps.get(path.id());
            String head = lap != null && lap.walking() ? "folkways.page.patrol.walking" : "folkways.page.patrol.head";
            rows.add(Board.Row.gauged(icon, title, Component.translatable(head, warded, stops.size()),
                warded, stops.size(), List.of(new Board.Act.Ping(stops.getFirst().at().block(level)))));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.patrol.count", Component.literal(Integer.toString(count))),
                new Board.Figure("folkways.page.patrol.warded", Component.literal(Integer.toString(held)))),
            rows,
            Optional.of(Component.translatable("folkways.page.patrol.empty"))));
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
        watched(resident, at, FolkwaysVanillaConfig.patrolWardTicks());
    }

    void watched(UUID resident, long at, long duration) {
        lastWatch.put(resident, at);
        dutyUntil.put(resident, at + duration);
    }

    // A patroller on duty - one who stood watch while its last ward still holds - goes for monsters near it.
    public List<Urge> urges(Worker who, ColonyView view) {
        Mob body = who.body();
        if (!(body.level() instanceof ServerLevel level) || !armed(who)) {
            return List.of();
        }
        Optional<Mob> foe = onDuty(who.resident().id(), level.getGameTime())
            ? nearestFoe(level, body, ENGAGE_RANGE + who.effects().value(PatrolContent.ALERT_RANGE))
            : attacker(body);
        if (foe.isEmpty()) {
            return List.of();
        }
        Optional<Stances> reach = Stances.of(level, Reach.workableCells(level, cellOf(level, foe.get())));
        if (reach.isEmpty()) {
            return List.of();
        }
        double weight = body.getHealth() > body.getMaxHealth() * WOUNDED ? STAND_GROUND : Urge.NOW;
        Mob target = foe.get();
        if (who.effects().value(PatrolContent.ALERT_RANGE) > 0) {
            alerts.put(target.getUUID(), level.getGameTime() + 100);
        }
        Stances stances = reach.get();
        return List.of(new Urge(ENGAGE, weight, true, () -> new StrikeNode(level, target, stances)));
    }

    private boolean onDuty(UUID resident, long now) {
        Long at = lastWatch.get(resident);
        return at != null && now <= dutyUntil.getOrDefault(resident, Long.MIN_VALUE);
    }

    private static boolean armed(Worker who) {
        return ResidentKinds.barehanded(who.resident().kind(), Optional.of(PatrolContent.trade()))
            || !who.held(PatrolContent.WEAPON).isEmpty();
    }

    // An armed resident away from patrol may defend itself, without becoming a roaming hunter.
    private static Optional<Mob> attacker(Mob body) {
        return Optional.ofNullable(body.getLastHurtByMob())
            .filter(Mob.class::isInstance).map(Mob.class::cast)
            .filter(mob -> body.tickCount - body.getLastHurtByMobTimestamp() <= 100
                && Threats.hostile(mob, body) && body.distanceToSqr(mob) <= ENGAGE_RANGE * ENGAGE_RANGE
                && body.hasLineOfSight(mob));
    }

    private Optional<Mob> nearestFoe(ServerLevel level, Mob body, double range) {
        alerts.values().removeIf(until -> until < level.getGameTime());
        List<Mob> near = level.getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(Math.max(range, 24)),
            mob -> Threats.hostile(mob, body)
                && (body.distanceToSqr(mob) <= range * range
                    || alerts.containsKey(mob.getUUID()) && body.distanceToSqr(mob) <= 24 * 24));
        near.sort(Comparator.comparingDouble(body::distanceToSqr));
        for (Mob mob : near) {
            if (body.hasLineOfSight(mob) || alerts.containsKey(mob.getUUID())) {
                return Optional.of(mob);
            }
        }
        return Optional.empty();
    }

    // The block a mob stands in, where the level stores it: the ship's cell when it stands on one, as the cells
    // to strike it from are found among the ship's blocks.
    private static BlockPos cellOf(ServerLevel level, Entity entity) {
        return WorldSpaces.storage(level, WorldSpaces.at(entity)).orElse(entity.blockPosition());
    }

    private static UUID idOf(UUID route, int lap, int stop) {
        return UUID.nameUUIDFromBytes(("folkways:patrol/" + route + "/" + lap + "/" + stop)
            .getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        routes = Map.of();
        laps.clear();
        lastWatch.clear();
        dutyUntil.clear();
        alerts.clear();
    }
}
