package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.EngineMode;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Hops;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.core.engine.travel.graph.Roamer;
import io.github.izakyl.folkways.core.engine.travel.graph.Roaming;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class ColonyWays {

    private record Track(MinecraftServer server, ResourceKey<Level> dimension, UUID colony) {
    }

    private static final Map<Track, Roaming> GRAPHS = new LinkedHashMap<>();

    private ColonyWays() {
    }

    public static Ways of(ColonyData colony, ServerLevel level) {
        return EngineMode.singleThread() ? new Answering(colony, level) : graph(colony, level).ways();
    }

    private record Answering(ColonyData colony, ServerLevel level) implements Ways {

        @Override
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
            return answered(ways -> ways.journey(who, from, goals));
        }

        @Override
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals, Urgency urgency) {
            return answered(ways -> ways.journey(who, from, goals, urgency));
        }

        @Override
        public Faring anyoneReaches(Set<WorldPos> goals) {
            return answered(ways -> ways.anyoneReaches(goals));
        }

        private Faring answered(Function<Ways, Faring> asking) {
            return graph(colony, level).answered(asking, roamers(colony, level), hopsIn(colony),
                level.getGameTime());
        }
    }

    public static boolean advance(ColonyData colony, ServerLevel level) {
        return graph(colony, level).advance(roamers(colony, level), hopsIn(colony),
            level.getGameTime());
    }

    public static boolean answered(ColonyData colony, ServerLevel level) {
        Roaming graph = GRAPHS.get(new Track(level.getServer(), level.dimension(), colony.colonyId()));
        return graph != null && graph.answered();
    }

    public static void wrongEdge(ServerLevel level, WorldPos from, WorldPos to) {
        GRAPHS.forEach((track, graph) -> {
            if (track.server() == level.getServer() && track.dimension().equals(level.dimension())) {
                graph.wrongEdge(from, to);
            }
        });
    }

    public static void walkedEdge(ServerLevel level, WorldPos from, WorldPos to, int ticks) {
        GRAPHS.forEach((track, graph) -> {
            if (track.server() == level.getServer() && track.dimension().equals(level.dimension())) {
                graph.walkedEdge(from, to, ticks);
            }
        });
    }

    public static void forget(UUID colony) {
        GRAPHS.keySet().removeIf(track -> track.colony().equals(colony));
        Hops.forget(colony);
    }

    public static void forget(UUID colony, ResourceKey<Level> dimension) {
        GRAPHS.keySet().removeIf(track -> track.colony().equals(colony) && track.dimension().equals(dimension));
    }

    public static void forgetServer(MinecraftServer server) {
        GRAPHS.keySet().removeIf(track -> track.server() == server);
        Hops.forgetServer();
        Closures.forgetServer(server);
    }

    private static Roaming graph(ColonyData colony, ServerLevel level) {
        return GRAPHS.computeIfAbsent(
            new Track(level.getServer(), level.dimension(), colony.colonyId()),
            track -> new Roaming(level));
    }

    private static List<Roamer> roamers(ColonyData colony, ServerLevel level) {
        return roamers(colony.holdings().bodiesIn(level));
    }

    static List<Roamer> roamers(Iterable<Body> bodies) {
        Map<ResourceLocation, Roamer> byKind = new LinkedHashMap<>();
        for (Body body : bodies) {
            if (body.mob().isRemoved()) {
                continue;
            }
            Roamer current = byKind.get(body.kind().id());
            if (current == null || current.body().isPassenger() && !body.mob().isPassenger()) {
                byKind.put(body.kind().id(),
                    new Roamer(body.kind().id(), body.kind().locomotion(), body.mob()));
            }
        }
        return List.copyOf(byKind.values());
    }

    private static List<Hop> hopsIn(ColonyData colony) {
        return Hops.in(colony.works());
    }
}
