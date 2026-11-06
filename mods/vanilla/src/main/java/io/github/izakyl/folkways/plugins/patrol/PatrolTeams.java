package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.group.GroupMotion;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Drawn-route ownership stays in patrol; only the shared planner decides group destinations. */
final class PatrolTeams {
    private static final class Team {
        private final UUID id = UUID.randomUUID();
        private List<Stop> stops;
        private int stop;
        private int direction = 1;
        private GroupMotion motion;
        private Set<UUID> roster = Set.of();
        private boolean planningFailed;
        private UUID foe;
        private long engagingUntil;
        private Response response;
        private GroupMotion diversion;
        private long generation;
        private String status = "assembling";
    }

    private record Response(UUID target, long until, Set<UUID> members) {
    }

    private final Colony colony;
    private final PatrolPresence presence;
    private final Map<UUID, Team> teams = new LinkedHashMap<>();

    PatrolTeams(Colony colony, PatrolPresence presence) {
        this.colony = colony;
        this.presence = presence;
    }

    private List<Body> bodies(MinecraftServer server, UUID route) {
        List<Body> bodies = new ArrayList<>();
        for (UUID member : PatrolStaffing.of(colony).members(route)) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(member) != null) {
                    Bodies.of(level.getEntity(member)).ifPresent(bodies::add);
                }
            }
        }
        return List.copyOf(bodies);
    }

    void tick(MinecraftServer server, Map<UUID, List<Stop>> routes) {
        PatrolStaffing staffing = PatrolStaffing.of(colony);
        teams.keySet().removeIf(route -> !routes.containsKey(route) || !staffing.configured(route));
        for (Map.Entry<UUID, List<Stop>> route : routes.entrySet()) {
            UUID id = route.getKey();
            if (!staffing.configured(id)) {
                continue;
            }
            Team team = teams.computeIfAbsent(id, ignored -> new Team());
            if (!route.getValue().equals(team.stops)) {
                team.stops = route.getValue();
                team.stop = Math.min(team.stop, team.stops.size() - 1);
                team.motion = null;
                team.planningFailed = false;
            }
            List<Body> bodies = bodies(server, id);
            if (team.response != null && !responseValid(team, server.overworld().getGameTime())) {
                team.response = null;
                team.diversion = null;
                team.foe = null;
                team.generation++;
            }
            if (bodies.isEmpty()) {
                team.status = "waiting";
                continue;
            }
            Set<UUID> roster = Set.copyOf(bodies.stream().map(Body::id).toList());
            if (!roster.equals(team.roster)) {
                team.planningFailed = false;
                team.roster = roster;
            }
            if (team.motion == null) {
                if (team.planningFailed) {
                    continue;
                }
                try {
                    team.motion = new GroupMotion(this::validate, bodies, team.stops.get(team.stop).at());
                    team.generation++;
                } catch (IllegalArgumentException unavailable) {
                    team.status = "blocked";
                    team.planningFailed = true;
                    continue;
                }
            }
            long now = server.overworld().getGameTime();
            if (team.response == null && team.foe != null && (now >= team.engagingUntil
                || bodies.stream().noneMatch(body ->
                    SpottedTargets.of(colony).get(body.mob(), team.foe).isPresent()))) {
                team.foe = null;
            }
            boolean danger = bodies.stream().anyMatch(body -> body.mob().getHealth() <= body.mob().getMaxHealth() * .35
                || PatrolSupplies.of(colony).underway(body.id()));
            Map<UUID, WorldPos> old = team.motion.destinations();
            team.motion.tick(bodies, team.foe != null, danger);
            if (team.diversion != null) {
                Map<UUID, WorldPos> previous = team.diversion.destinations();
                team.diversion.tick(bodies, false, danger);
                if (!previous.equals(team.diversion.destinations())) {
                    team.generation++;
                }
            }
            if (!old.equals(team.motion.destinations())) {
                team.generation++;
            }
            team.status = team.motion.phase().name().toLowerCase(Locale.ROOT);
            if (team.response == null && team.motion.arrived()) {
                ServerLevel level = (ServerLevel) bodies.getFirst().mob().level();
                for (Body body : bodies) {
                    Wards.ward(level, team.stops.get(team.stop).at().block(level),
                        now + Math.round(FolkwaysVanillaConfig.patrolWardTicks()
                            * body.effects().value(PatrolContent.WARD_DURATION)),
                        FolkwaysVanillaConfig.patrolWardRadius()
                            + (int) body.effects().value(PatrolContent.WARD_RADIUS),
                        (int) body.effects().value(PatrolContent.COMFORT));
                    presence.watched(body.id(), now);
                }
                if (team.stops.size() > 1) {
                    if (team.stop == team.stops.size() - 1) {
                        team.direction = -1;
                    } else if (team.stop == 0) {
                        team.direction = 1;
                    }
                    team.stop += team.direction;
                    team.motion = null;
                }
            }
        }
    }

    private void validate(Body body, WorldPos at) {
        if (!body.mob().isAlive() || body.mob().isRemoved() || !at.in(body.mob().level())
            || !body.mob().level().hasChunkAt(at.block(body.mob().level()))) {
            throw new IllegalArgumentException("unavailable patrol team destination");
        }
    }

    boolean member(UUID resident) {
        return team(resident).isPresent();
    }

    private Optional<Team> team(UUID resident) {
        DefenseControl control = DefenseControl.of(colony);
        if (control.current(resident).isPresent() || control.reserved(resident) || control.failedWaiting(resident)) {
            return Optional.empty();
        }
        return PatrolStaffing.of(colony).targetOf(resident).map(teams::get);
    }

    Optional<Mob> foe(Worker worker) {
        return team(worker.resident().id()).filter(team -> team.foe != null
            && (team.response == null || responseValid(team, worker.body().level().getGameTime())))
            .flatMap(team -> SpottedTargets.of(colony).get(worker.body(), team.foe))
            .flatMap(spot -> SpottedTargets.of(colony).resolve(spot, worker.body()));
    }

    void engaging(Worker worker, Mob target) {
        team(worker.resident().id()).ifPresent(team -> {
            team.foe = target.getUUID();
            team.engagingUntil = worker.body().level().getGameTime() + 20;
        });
    }

    record Responder(UUID id, double distance, BooleanSupplier accept) {
    }

    List<Responder> responders(Mob sentry, Mob target, SpottedTargets.Spot spot, Predicate<UUID> availableMember) {
        List<Responder> responders = new ArrayList<>();
        long now = sentry.level().getGameTime();
        double radius = FolkwaysVanillaConfig.sentryCallRadius();
        for (UUID route : teams.keySet().stream().sorted().toList()) {
            Team team = teams.get(route);
            List<Body> bodies = bodies(sentry.getServer(), route);
            if (bodies.isEmpty() || team.foe != null || team.motion == null
                || PatrolStaffing.of(colony).positions(route).stream()
                    .anyMatch(position -> position.status().equals("controlled"))
                || bodies.stream().anyMatch(body -> !availableMember.test(body.id())
                    || body.mob().level() != sentry.level()
                    || body.mob().getHealth() <= body.mob().getMaxHealth() * .35
                    || body.mob().distanceToSqr(sentry) > radius * radius)) {
                continue;
            }
            double distance = bodies.stream().mapToDouble(body -> body.mob().distanceToSqr(sentry))
                .min().orElseThrow();
            responders.add(new Responder(route, distance, () -> {
                try {
                    WorldPos destination = WorldPos.of(sentry.level(), BlockPos.containing(spot.position()));
                    team.diversion = new GroupMotion(this::validate, bodies, destination);
                } catch (IllegalArgumentException unavailable) {
                    return false;
                }
                team.foe = target.getUUID();
                team.response = new Response(target.getUUID(), now + FolkwaysVanillaConfig.sentryCallTicks(),
                    Set.copyOf(bodies.stream().map(Body::id).toList()));
                team.generation++;
                return true;
            }));
        }
        return List.copyOf(responders);
    }

    int responding(UUID target, long now) {
        return (int) teams.values().stream()
            .filter(team -> target.equals(team.foe) && responseValid(team, now)).count();
    }

    boolean responding(UUID resident) {
        return team(resident).filter(team -> responseValid(team,
            ServerLifecycleHooks.getCurrentServer().overworld().getGameTime())).isPresent();
    }

    private boolean responseValid(Team team, long now) {
        Response response = team.response;
        if (response == null || now >= response.until()) {
            return false;
        }
        DefenseControl control = DefenseControl.of(colony);
        return response.members().stream().allMatch(member -> !control.reserved(member)
            && !control.failedWaiting(member) && control.current(member).isEmpty()
            && PatrolStaffing.of(colony).targetOf(member).map(teams::get).orElse(null) == team)
            && response.members().stream().anyMatch(member -> {
                for (ServerLevel level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
                    if (level.getEntity(member) instanceof Mob body
                        && SpottedTargets.of(colony).get(body, response.target()).isPresent()) {
                        return true;
                    }
                }
                return false;
            });
    }

    BooleanSupplier permission(Worker worker, Mob target) {
        Team team = team(worker.resident().id()).orElse(null);
        if (team == null) {
            return () -> true;
        }
        Response response = team.response;
        return () -> team(worker.resident().id()).orElse(null) == team
            && (response == null ? team.response == null && insideLeash(team, worker, target)
                : team.response == response && response.target().equals(target.getUUID())
                && responseValid(team, worker.body().level().getGameTime()));
    }

    private boolean insideLeash(Team team, Worker worker, Mob target) {
        return team.motion != null && WorldSpaces.world(worker.body().level(), team.motion.anchor())
            .filter(at -> at.distanceToSqr(target.position()) <= 12 * 12).isPresent();
    }

    void retry(UUID route) {
        Team team = teams.get(route);
        if (team != null) {
            team.motion = null;
            team.planningFailed = false;
            team.diversion = null;
            team.response = null;
            team.foe = null;
            team.generation++;
        }
    }

    String status(UUID route) {
        Team team = teams.get(route);
        return team == null ? "waiting" : team.status;
    }

    int stop(UUID route) {
        Team team = teams.get(route);
        return team == null ? 0 : team.stop;
    }

    List<Urge> urges(Worker worker) {
        Optional<Team> found = team(worker.resident().id());
        if (found.isEmpty() || found.get().motion == null) {
            return List.of();
        }
        Team team = found.get();
        UUID resident = worker.resident().id();
        DefenseControl control = DefenseControl.of(colony);
        if (control.current(resident).isPresent() || control.reserved(resident) || control.failedWaiting(resident)
            || PatrolSupplies.of(colony).underway(resident)) {
            return List.of();
        }
        GroupMotion motion = team.diversion == null ? team.motion : team.diversion;
        if (motion.phase() == GroupMotion.Phase.BLOCKED || motion.phase() == GroupMotion.Phase.WAITING) {
            return List.of();
        }
        WorldPos at = motion.destinations().get(resident);
        if (at == null) {
            return List.of();
        }
        long generation = team.generation;
        ResourceLocation intent = ResourceLocation.fromNamespaceAndPath("folkways",
            "patrol_team/" + team.id + "/" + resident + "/" + generation);
        return List.of(new Urge(intent, 1.5, true, () -> new Node() {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PatrolContent.ID,
                new WorkSite.AtBlock(at), Stances.at(at), (Workload.Continuous) (level, who) -> ready(level))
                .vocation(PatrolContent.trade()).doing(PatrolContent.PATROLLING).done();

            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public boolean mountedWork() {
                return true;
            }

            @Override
            public boolean directTravel() {
                return true;
            }

            @Override
            public boolean staysAtWorkSite() {
                return true;
            }

            @Override
            public boolean ready(ServerLevel level) {
                return team.generation == generation && team(resident).orElse(null) == team;
            }

            @Override
            public Outcome commit(ServerLevel level, Worker who) {
                return Outcome.done();
            }
        }));
    }
}
