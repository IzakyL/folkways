package io.github.izakyl.folkways.core.travel.internal;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.TrafficFeedback;
import io.github.izakyl.folkways.core.travel.api.TravelSources;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraph;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public static final TrafficFeedback FEEDBACK = new TrafficFeedback() {

        @Override
        public void wrongEdge(ServerLevel level, WorldPos from, WorldPos to) {
            ColonyWays.wrongEdge(level, from, to);
        }

        @Override
        public void walkedEdge(ServerLevel level, WorldPos from, WorldPos to, int ticks) {
            ColonyWays.walkedEdge(level, from, to, ticks);
        }
    };

    private static boolean singleThread;

    public static void singleThread(boolean enabled) {
        singleThread = enabled;
    }

    private ColonyWays() {
    }

    public static Ways of(TravelSources colony, ServerLevel level) {
        return singleThread ? new Answering(colony, level) : graph(colony, level).ways();
    }

    /** Reads only an already published graph; never creates or advances a roaming search. */
    public static Optional<WayGraph> published(UUID colony, ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("waypoint diagnostics require the server thread");
        }
        Roaming roaming = GRAPHS.get(new Track(level.getServer(), level.dimension(), colony));
        return roaming == null ? Optional.empty() : Optional.of((WayGraph) roaming.ways());
    }

    private record Answering(TravelSources colony, ServerLevel level) implements Ways {

        @Override
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
            return answered(ways -> ways.journey(who, from, goals));
        }

        @Override
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals, Urgency urgency) {
            return answered(ways -> ways.journey(who, from, goals, urgency));
        }

        @Override
        public TransportTravel estimates() {
            return graph(colony, level).ways().estimates();
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

    public static boolean advance(TravelSources colony, ServerLevel level) {
        return graph(colony, level).advance(roamers(colony, level), hopsIn(colony),
            level.getGameTime());
    }

    public static boolean answered(TravelSources colony, ServerLevel level) {
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

    private static Roaming graph(TravelSources colony, ServerLevel level) {
        return GRAPHS.computeIfAbsent(
            new Track(level.getServer(), level.dimension(), colony.colonyId()),
            track -> new Roaming(level));
    }

    private static List<Roamer> roamers(TravelSources colony, ServerLevel level) {
        return roamers(colony.bodiesIn(level));
    }

    public static List<Roamer> roamers(Iterable<Body> bodies) {
        Map<ResourceLocation, Roamer> byKind = new LinkedHashMap<>();
        for (Body body : bodies) {
            if (body.mob().isRemoved()) {
                continue;
            }
            Roamer current = byKind.get(body.routeKind());
            if (current == null || current.body().isPassenger() && !body.mob().isPassenger()) {
                byKind.put(body.routeKind(),
                    new Roamer(body.routeKind(), body.locomotion(), body.mob()));
            }
        }
        return List.copyOf(byKind.values());
    }

    private static List<Hop> hopsIn(TravelSources colony) {
        return colony.hops();
    }
}
