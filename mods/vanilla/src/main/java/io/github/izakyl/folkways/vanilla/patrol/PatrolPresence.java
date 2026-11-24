package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Asking;
import io.github.izakyl.folkways.core.colony.api.Cadence;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.Threats;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentWeapons;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
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
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class PatrolPresence implements HoldingKind {


    static final ResourceLocation ENGAGE = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "engage");

    private static final int PERIOD = 100;

    private static final double GUARD_LEASH = 12.0;

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
    private final PatrolSupplies supplies;
    private final PatrolTeams teams;

    private final Asking asking;

    private final AtomicBoolean offerPending = new AtomicBoolean();

    // When routes are read again within each period, spread against every other cron; see Cadence.
    private final Cadence.Cron cron;
    private boolean swept;

    private final Map<UUID, Long> lastWatch = new ConcurrentHashMap<>();

    private final Map<UUID, Long> dutyUntil = new ConcurrentHashMap<>();

    private final Map<UUID, Lap> laps = new ConcurrentHashMap<>();

    private record RouteWorker(Worker worker, long tick) {
    }

    private record Call(Spot spot, long until) {
    }

    private final Map<UUID, RouteWorker> routeWorkers = new LinkedHashMap<>();
    private final Map<UUID, Call> calls = new LinkedHashMap<>();
    private final Map<UUID, Long> fighting = new LinkedHashMap<>();

    private volatile Map<UUID, List<Stop>> routes = Map.of();

    PatrolPresence(Colony colony) {
        this.colony = colony;
        this.supplies = new PatrolSupplies(colony, () -> offerPending.set(true));
        this.teams = new PatrolTeams(colony, this);
        this.asking = new Asking(colony, PatrolContent.ID, this::ended);
        this.cron = Cadence.claim(colony, PERIOD);
    }

    public void tick(MinecraftServer server) {
        supplies.tick(server);
        long now = server.overworld().getGameTime();
        routeWorkers.values().removeIf(candidate -> now - candidate.tick() > 40);
        calls.values().removeIf(call -> now >= call.until());
        if (!swept || cron.due(server)) {
            swept = true;
            refresh(server);
            offerPending.set(true);
        }
        teams.tick(server, routes);
        if (offerPending.getAndSet(false)) {
            asking.offer(server, this::goals);
        }
    }

    PatrolSupplies supplies() {
        return supplies;
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
    // Patrollers' supplies are asked along with the laps.
    public List<Grown> goals(ColonyView view) {
        ServerLevel level = view.level();
        long now = level.getGameTime();
        long due = FolkwaysVanillaConfig.patrolWardTicks() / 2;
        List<Grown> rounds = new ArrayList<>(supplies.goals(view));
        routes.forEach((route, stops) -> {
            if (PatrolStaffing.of(colony).configured(route)) {
                return;
            }
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

    // A route's card staffs it: its headcount, the profiles allowed on it, and a retry once its team stalled.
    @Override
    public List<Card.Element> elements() {
        return StaffingCard.elements(true);
    }

    @Override
    public List<Card.Row> rows(Held held, String source, ColonyView view) {
        return route(held) ? StaffingCard.rows(colony, held, source) : List.of();
    }

    @Override
    public Optional<Value> parameter(Held held, String parameter) {
        return route(held) ? StaffingCard.parameter(colony, held, parameter) : Optional.empty();
    }

    @Override
    public Optional<List<DefinedFacilities.Problem>> setParameter(Held held, String parameter, Value value) {
        return route(held) ? StaffingCard.setParameter(colony, held, parameter, value) : Optional.empty();
    }

    @Override
    public Optional<List<DefinedFacilities.Problem>> act(Held held, ServerPlayer by, String routine,
            Map<String, Value> arguments) {
        return route(held) ? StaffingCard.act(colony, held, routine, arguments) : Optional.empty();
    }

    private static boolean route(Held held) {
        return held.delegation().equals(PatrolContent.BEAT) && held.path().isPresent();
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        if (!route(held)) {
            return List.of();
        }
        UUID beat = held.id();
        PatrolStaffing staffing = PatrolStaffing.of(colony);
        List<Line> lines = new ArrayList<>(staffing.lines(beat));
        if (staffing.configured(beat)) {
            lines.add(teamLine(beat));
        }
        return List.copyOf(lines);
    }

    private Line teamLine(UUID beat) {
        return Line.text(Component.translatable("folkways.staffing.team",
            Component.translatable("folkways.staffing." + teams.status(beat)), teams.stop(beat) + 1));
    }

    PatrolTeams teams() {
        return teams;
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
        supplies.ended(node);
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

    // A patroller on duty - one who stood watch while its last ward still holds - goes for monsters
    // near it.
    public List<Urge> urges(Worker who, ColonyView view) {
        Mob body = who.body();
        if (!(body.level() instanceof ServerLevel level)) {
            return List.of();
        }
        UUID resident = who.resident().id();
        if (who.route().stream().anyMatch(WatchNode.class::isInstance) || teams.member(resident)) {
            routeWorkers.put(resident, new RouteWorker(who, level.getGameTime()));
        }
        SpottedTargets spots = SpottedTargets.of(colony);
        DefenseControl control = DefenseControl.of(colony);
        Optional<? extends DefenseControl.Order> order = control.current(who.resident().id());
        boolean failedWaiting = order.isEmpty() && control.failedWaiting(who.resident().id());
        supplies.resupply(
            who,
            order,
            lastWatch.getOrDefault(who.resident().id(), Long.MIN_VALUE),
            failedWaiting ? Long.MIN_VALUE : dutyUntil.getOrDefault(who.resident().id(), Long.MIN_VALUE));
        List<Urge> idle = new ArrayList<>();
        idle.addAll(teams.urges(who));
        if (SentryPosts.of(colony).posting(level, resident) && order.isEmpty() && !failedWaiting) {
            return idle;
        }
        Optional<? extends DefenseControl.Order> guard =
            order.filter(current -> current.guarding() || current.defensiveOnly());
        // Failed waiting has no navigation handle, but must not resume an old patrol duty or supply
        // trip.
        // Stopped and reforming squads may retaliate inside their fixed slot leash, never scan for
        // a new foe.
        Optional<Mob> foe =
            failedWaiting || guard.filter(DefenseControl.Order::defensiveOnly).isPresent()
            ? attacker(body).filter(target -> insideLeash(level, guard, WorldSpaces.at(target)))
            : guard.isPresent() || onDuty(who.resident().id(), level.getGameTime())
            || routeWorkers.containsKey(resident) || teams.member(resident)
            ? called(who).filter(target -> ResidentWeapons.planned(colony, who, target))
                .or(() -> teams.foe(who).filter(target -> ResidentWeapons.planned(colony, who, target)))
                .or(() -> nearestFoe(level, who, guard))
            : attacker(body);
        foe = foe.filter(target -> teams.permission(who, target).getAsBoolean()
            && ResidentWeapons.planned(colony, who, target));
        if (foe.isPresent()) {
            spots.observe(body);
        }
        if (foe.isEmpty() || !ResidentWeapons.armed(who)) {
            return idle;
        }
        Optional<Stances> reach = battleStances(level, who, foe.get(), guard);
        if (reach.isEmpty()) {
            return idle;
        }
        boolean standsGround = body.getHealth() > body.getMaxHealth() * WOUNDED;
        double weight = standsGround ? STAND_GROUND : Urge.NOW;
        // A wounded guard's combat urge cannot displace its position order. Only actual combat
        // priority
        // renews the group pause; fear remains free to take over independently.
        if (standsGround) {
            guard.ifPresent(DefenseControl.Order::engaging);
            teams.engaging(who, foe.get());
        }
        Mob target = foe.get();
        Call response = calls.get(resident);
        fighting.put(resident, level.getGameTime() + 20);
        BooleanSupplier teamPermission = teams.permission(who, target);
        BooleanSupplier authorized =
            () -> teamPermission.getAsBoolean()
                && (response == null || calls.get(resident) == response && called(who).isPresent())
                && (SpottedTargets.sees(body, target) || spots.get(body, target.getUUID()).isPresent());
        Stances stances = reach.get();
        // A waypoint detour can pull the defender out of sight of the foe before it reaches striking distance.
        WorldPos from = WorldSpaces.at(body);
        boolean directTravel = stances.cells().stream().allMatch(from::sameRealm);
        ResourceLocation intent =
            ResourceLocation.fromNamespaceAndPath(ENGAGE.getNamespace(), ENGAGE.getPath() + "/" + target.getUUID());
        return List.of(
            new Urge(
            intent,
            weight,
            true,
            () ->
                body instanceof ResidentEntity
                    ? new StrikeNode(level, target, stances, authorized, directTravel)
                    : new ToolStrikeNode(level, target, stances, authorized, directTravel)));
    }

    private boolean onDuty(UUID resident, long now) {
        Long at = lastWatch.get(resident);
        return at != null && now <= dutyUntil.getOrDefault(resident, Long.MIN_VALUE);
    }

    // An armed resident away from patrol may defend itself, without becoming a roaming hunter.
    private static Optional<Mob> attacker(Mob body) {
        return Optional.ofNullable(body.getLastHurtByMob())
            .filter(Mob.class::isInstance)
            .map(Mob.class::cast)
            .filter(
            mob ->
                body.tickCount - body.getLastHurtByMobTimestamp() <= 100
                    && Threats.hostile(mob, body)
                    && body.distanceToSqr(mob) <= GUARD_LEASH * GUARD_LEASH
                    && body.hasLineOfSight(mob));
    }

    private Optional<Mob> nearestFoe(
        ServerLevel level, Worker worker, Optional<? extends DefenseControl.Order> guard) {
        Mob body = worker.body();
        SpottedTargets spots = SpottedTargets.of(colony);
        List<Mob> seen = new ArrayList<>(spots.observe(body));
        for (Spot spot : spots.targets(body)) {
            spots.resolve(spot, body).filter(target -> ResidentWeapons.ranged(worker, target)
                && (ResidentWeapons.indirect(worker, target) || ResidentWeapons.longRange(worker, target)
                && body.hasLineOfSight(target) && body.distanceTo(target) <= ResidentWeapons.range(worker, target)))
                .filter(target -> !seen.contains(target)).ifPresent(seen::add);
        }
        return seen.stream().filter(mob -> insideLeash(level, guard, WorldSpaces.at(mob)))
            .filter(mob -> ResidentWeapons.planned(colony, worker, mob))
            .min(Comparator.comparingDouble(body::distanceToSqr));
    }

    private Optional<Mob> called(Worker worker) {
        UUID id = worker.resident().id();
        Call call = calls.get(id);
        if (call == null) {
            return Optional.empty();
        }
        DefenseControl control = DefenseControl.of(colony);
        if (worker.body().level().getGameTime() >= call.until() || control.current(id).isPresent()
            || control.reserved(id) || control.failedWaiting(id) || SentryPosts.of(colony).assigned(id)) {
            calls.remove(id);
            return Optional.empty();
        }
        Optional<Mob> target = SpottedTargets.of(colony).get(worker.body(), call.spot().target())
            .flatMap(spot -> SpottedTargets.of(colony).resolve(spot, worker.body()));
        if (target.isEmpty()) {
            calls.remove(id);
        }
        return target;
    }

    void call(Mob sentry, Mob target) {
        ServerLevel level = (ServerLevel) sentry.level();
        Optional<Spot> spot = SpottedTargets.of(colony).get(sentry, target.getUUID());
        if (spot.isEmpty()) {
            return;
        }
        calls.entrySet().removeIf(entry -> entry.getValue().until() <= level.getGameTime());
        long responding = calls.values().stream().filter(call -> call.spot().target().equals(target.getUUID())).count()
            + teams.responding(target.getUUID(), level.getGameTime());
        int available = Math.max(0, FolkwaysVanillaConfig.sentryCallResponders() - (int) responding);
        List<PatrolTeams.Responder> candidates = new ArrayList<>(teams.responders(sentry, target, spot.get(), id -> {
            RouteWorker candidate = routeWorkers.get(id);
            return candidate != null && level.getGameTime() - candidate.tick() <= 40
                && ResidentWeapons.armed(candidate.worker())
                && fighting.getOrDefault(id, 0L) < level.getGameTime()
                && !supplies.underway(id);
        }));
        DefenseControl control = DefenseControl.of(colony);
        double radius = FolkwaysVanillaConfig.sentryCallRadius();
        routeWorkers.values().stream()
            .filter(candidate -> level.getGameTime() - candidate.tick() <= 40)
            .map(RouteWorker::worker)
            .filter(worker -> worker.body().level() == level && worker.body().isAlive() && !worker.body().isRemoved()
                && worker.body().distanceToSqr(sentry) <= radius * radius
                && worker.body().getHealth() > worker.body().getMaxHealth() * WOUNDED
                && ResidentWeapons.armed(worker) && !calls.containsKey(worker.resident().id())
                && !teams.member(worker.resident().id())
                && fighting.getOrDefault(worker.resident().id(), 0L) < level.getGameTime()
                && !SentryPosts.of(colony).assigned(worker.resident().id())
                && control.current(worker.resident().id()).isEmpty() && !control.reserved(worker.resident().id())
                && !control.failedWaiting(worker.resident().id()))
            .forEach(worker -> candidates.add(new PatrolTeams.Responder(worker.resident().id(),
                worker.body().distanceToSqr(sentry), () -> {
                    calls.put(worker.resident().id(),
                        new Call(spot.get(), level.getGameTime() + FolkwaysVanillaConfig.sentryCallTicks()));
                    return true;
                })));
        candidates.sort(Comparator.comparingDouble(PatrolTeams.Responder::distance)
            .thenComparing(PatrolTeams.Responder::id));
        for (PatrolTeams.Responder candidate : candidates) {
            if (available == 0) {
                break;
            }
            if (candidate.accept().getAsBoolean()) {
                available--;
            }
        }
    }

    boolean engaging(UUID resident) {
        return fighting.getOrDefault(resident, 0L)
            >= ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
    }

    boolean responding(UUID resident) {
        return calls.containsKey(resident) || teams.responding(resident);
    }

    private static boolean insideLeash(ServerLevel level, Optional<? extends DefenseControl.Order> guard, WorldPos at) {
        return guard.isEmpty()
            || guard.get().destination().sameRealm(at)
            && WorldSpaces.world(level, guard.get().destination())
            .flatMap(
            origin ->
                WorldSpaces.world(level, at)
                    .map(
                    point ->
                        origin.distanceToSqr(point)
                            <= GUARD_LEASH * GUARD_LEASH))
            .orElse(false);
    }

    private static Optional<Stances> battleStances(
        ServerLevel level, Worker who, Mob foe, Optional<? extends DefenseControl.Order> guard) {
        Mob body = who.body();
        Set<BlockPos> cells = new LinkedHashSet<>();
        if (ResidentWeapons.ranged(who, foe)) {
            double range = ResidentWeapons.range(who, foe);
            BlockPos here = cellOf(level, body);
            if (body.distanceTo(foe) <= range
                && (body.hasLineOfSight(foe) || ResidentWeapons.indirect(who, foe)) && Footing.withFeetAt(level, here)) {
                // Ranged guards fire from their existing footing instead of walking into melee.
                cells.add(here);
            } else {
                BlockPos target = cellOf(level, foe);
                var towardBody = body.position().subtract(foe.position()).multiply(1, 0, 1).normalize();
                for (int distance = 2; distance < range; distance++) {
                    for (double turn : new double[] {-.5, 0, .5}) {
                        var feet = Vec3.atBottomCenterOf(target).add(towardBody.yRot((float) turn).scale(distance));
                        for (int dy = -2; dy <= 2; dy++) {
                            BlockPos cell = BlockPos.containing(feet).offset(0, dy, 0);
                            if (level.hasChunkAt(cell)
                                && Footing.withFeetAt(level, cell)
                                && Reach.inSight(level, cell, target)) {
                                cells.add(cell);
                            }
                        }
                    }
                }
            }
        } else {
            // Reaching a stance permits 0.9 horizontal and 1.0 vertical displacement. Compare
            // real world positions and leave that room before the native attack's strict range
            // check.
            double safeReach = Math.max(0, ResidentWeapons.range(who, foe) - Math.hypot(.9, 1.0));
            for (BlockPos cell : Reach.workableCells(level, cellOf(level, foe))) {
                if (WorldSpaces.world(level, WorldSpaces.at(level, cell))
                    .filter(point -> point.distanceToSqr(foe.position()) <= safeReach * safeReach)
                    .isPresent()) {
                    cells.add(cell);
                }
            }
        }
        cells.removeIf(cell -> !insideLeash(level, guard, WorldSpaces.at(level, cell)));
        return Stances.of(cells.stream().map(cell -> WorldSpaces.at(level, cell)).toList());
    }

    // The block a mob stands in, where the level stores it: the ship's cell when it stands on one, as the cells
    // to strike it from are found among the ship's blocks.
    private static BlockPos cellOf(ServerLevel level, Entity entity) {
        return WorldSpaces.storage(level, WorldSpaces.at(entity)).orElse(entity.blockPosition());
    }

    private static UUID idOf(UUID route, int lap, int stop) {
        return UUID.nameUUIDFromBytes(("folkways_vanilla:patrol/" + route + "/" + lap + "/" + stop)
            .getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        supplies.close(why);
        routes = Map.of();
        laps.clear();
        lastWatch.clear();
        dutyUntil.clear();
        routeWorkers.clear();
        calls.clear();
        fighting.clear();
    }
}
