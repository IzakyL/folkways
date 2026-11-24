package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** A definition, written by a player or supplied for a loaded entity, as its card reaches it. */
record DefinedOperating(DefinedFacilities service, FacilityDefinition facility) implements Operating {
    private static final int PATH_POINTS = 48;
    private static final double MAX_RING = 512;

    static Optional<DefinedOperating> find(Colony colony, UUID id) {
        return DefinedFacilities.of(colony)
            .flatMap(service -> service.facility(id).map(facility -> new DefinedOperating(service, facility)));
    }

    static List<DefinedOperating> all(Colony colony) {
        return DefinedFacilities.of(colony).map(service -> service.facilities().stream()
            .filter(facility -> service.runtime(facility.id()).map(RuntimeFacilities.RuntimeFacility::listed).orElse(true))
            .map(facility -> new DefinedOperating(service, facility)).toList()).orElse(List.of());
    }

    @Override
    public UUID id() {
        return facility.id();
    }

    @Override
    public Component title() {
        return service.runtime(facility.id()).map(RuntimeFacilities.RuntimeFacility::title)
            .orElseGet(() -> Component.literal(facility.name()));
    }

    @Override
    public Card card() {
        return facility.card();
    }

    @Override
    public Optional<ServerLevel> loaded(MinecraftServer server) {
        return facility.site().level(server).filter(facility.extent()::loaded);
    }

    @Override
    public Map<String, Value> parameters() {
        return facility.parameters();
    }

    @Override
    public Map<String, Boolean> switches() {
        return service.triggerStates(facility.name());
    }

    @Override
    public List<String> running() {
        return service.running(facility.name());
    }

    @Override
    public Optional<Recent> recent() {
        return service.recent(facility.name()).map(recent -> new Recent(recent.routine(), recent.ending(),
            recent.reason().filter(reason -> reason.length() <= 256)));
    }

    @Override
    public Optional<String> disabled(String routine, Map<String, Value> enabledWhen) {
        return service.disabled(facility.name(), routine, enabledWhen);
    }

    @Override
    public List<DefinedFacilities.Problem> run(ServerPlayer by, String routine, Map<String, Value> arguments) {
        var problems = service.run(facility.name(), routine, arguments);
        if (problems.isEmpty()) {
            service.runtime(facility.id()).flatMap(RuntimeFacilities.RuntimeFacility::accepted)
                .ifPresent(hook -> hook.run(by, routine));
        }
        return problems;
    }

    @Override
    public List<DefinedFacilities.Problem> setParameter(String parameter, Value value) {
        return value.equals(facility.parameters().get(parameter)) ? List.of()
            : service.setParameter(facility.name(), parameter, value);
    }

    @Override
    public List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled) {
        return service.toggle(facility.name(), trigger, enabled);
    }

    @Override
    public Map<String, Reading> readings(ServerLevel level) {
        var readings = new java.util.LinkedHashMap<>(facility.readings(level));
        var resolved = Posts.declared(level, facility);
        facility.posts().keySet().forEach(name -> readings.put("posts." + name + ".staffed",
            world -> Optional.of(Value.of(resolved.stream().anyMatch(post ->
                post.key().name().equals(name) && service.posts().staffed(world, post))))));
        return readings;
    }

    @Override
    public Optional<double[]> range(ServerLevel level, String reading) {
        Map<String, Control> controls = facility.resolve(level);
        return controls.get(reading) instanceof Signal signal && signal.type() instanceof SignalType.Real real
            ? Optional.of(new double[] {real.min(), real.max()}) : Optional.empty();
    }

    @Override
    public List<Overlay> overlays(ServerLevel level) {
        Map<String, Reading> sources = facility.readings(level);
        List<Overlay> overlays = new ArrayList<>();
        for (Card.Overlay overlay : facility.card().overlays()) {
            switch (overlay) {
                case Card.Overlay.Range range -> radius(level, range.reading(), sources)
                    .flatMap(radius -> center(level).map(center -> new Overlay.Ring(center, radius)))
                    .ifPresent(overlays::add);
                case Card.Overlay.Path path -> {
                    var use = facility.routines().get(path.routine());
                    if (use != null) {
                        var line = use.routine().equals(Routines.SCRIPT)
                            ? FacilitySamples.path(facility, path.routine())
                            : Routines.of(use.routine()).flatMap(routine ->
                                routine.path(level, facility, use.resolve(facility.parameters())));
                        line
                            .map(points -> points.struck(level))
                            .map(points -> new Overlay.Path(thin(points.points()), points.endsAtMark()))
                            .ifPresent(overlays::add);
                    }
                }
            }
        }
        return overlays;
    }

    // A parameter's number, a scan's range, or a numeric reading's value.
    private Optional<Double> radius(ServerLevel level, String name, Map<String, Reading> sources) {
        Value value = facility.parameters().get(name);
        FacilityDefinition.Probed probe = facility.probes().get(name);
        if (value == null && probe != null && probe.source().isEmpty()) {
            var configured = Readings.probe(probe.probe());
            if (configured.isPresent()) {
                var range = configured.get().range(probe.config());
                if (range.isPresent()) {
                    return Optional.of(range.getAsDouble());
                }
            }
        }
        if (value == null) {
            value = Optional.ofNullable(sources.get(name)).flatMap(reading -> reading.read(level)).orElse(null);
        }
        return value instanceof Value.Real real && real.value() > 0 && Double.isFinite(real.value())
            ? Optional.of(Math.min(real.value(), MAX_RING)) : Optional.empty();
    }

    private Optional<Vec3> center(ServerLevel level) {
        var published = FacilitySamples.readings(facility);
        for (var routine : facility.routines().entrySet()) {
            if (routine.getValue().routine().equals(Routines.SCRIPT)
                    && published.get(routine.getKey() + ".center") instanceof Value.Vector(List<Double> xyz)
                    && xyz.size() == 3) {
                return Optional.of(new Vec3(xyz.get(0), xyz.get(1), xyz.get(2)));
            }
        }
        return facility.routines().values().stream()
            .flatMap(use -> Routines.of(use.routine())
                .flatMap(routine -> routine.overlayCenter(level, facility, use.resolve(facility.parameters()))).stream())
            .findFirst()
            .or(() -> WorldSpaces.world(level, facility.anchor()).map(bottom -> bottom.add(0, .5, 0)));
    }

    // Keeps the packet small: at most PATH_POINTS points, always with both ends.
    private static List<Vec3> thin(List<Vec3> path) {
        if (path.size() <= PATH_POINTS) {
            return path;
        }
        List<Vec3> kept = new ArrayList<>();
        for (int i = 0; i < PATH_POINTS - 1; i++) {
            kept.add(path.get((int) ((long) i * (path.size() - 1) / (PATH_POINTS - 1))));
        }
        kept.add(path.getLast());
        return kept;
    }
}
