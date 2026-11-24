package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

final class ScriptAwait implements Node {

    private final NodeSpec spec;
    private final FacilityDefinition facility;
    private final FacilityScripts.Await action;
    private long deadline = Long.MIN_VALUE;

    ScriptAwait(FacilityDefinition facility, FacilityScripts.Await action) {
        this.facility = facility;
        this.action = action;
        spec = NodeSpec.of(UUID.randomUUID(), DefinedFacilities.ID, new WorkSite.AtBlock(facility.site()), Stances.WHEREVER,
            Workload.Once.of(0)).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (deadline == Long.MIN_VALUE) {
            deadline = level.getGameTime() + action.timeout();
        }
        return matches(level, facility, action) ? Outcome.done() : Outcome.failed(FacilityRefusal.UNSETTLED);
    }

    static boolean matches(ServerLevel level, FacilityDefinition facility, FacilityScripts.Await action) {
        String name = action.reading();
        var reading = facility.readings(level).get(name);
        Optional<Value> observed = reading == null ? Optional.empty() : reading.read(level);
        return observed.filter(value -> action.within() > 0 ? near(action.value(), value, action.within())
            : signal(level, facility, name).map(signal -> signal.type().agrees(action.value(), value))
                .orElseGet(() -> action.value().equals(value))).isPresent();
    }

    private static boolean near(Value target, Value observed, double within) {
        return switch (target) {
            case Value.Real(double want) -> observed instanceof Value.Real(double got) && Math.abs(want - got) <= within;
            case Value.Vector(java.util.List<Double> want) -> {
                if (!(observed instanceof Value.Vector(java.util.List<Double> got)) || got.size() != want.size()) {
                    yield false;
                }
                for (int i = 0; i < want.size(); i++) {
                    if (Math.abs(want.get(i) - got.get(i)) > within) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> target.equals(observed);
        };
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        long remaining = deadline - level.getGameTime();
        return why == FacilityRefusal.UNSETTLED && remaining > 0
            ? Recourse.waitFor((int) Math.min(10, remaining)) : Recourse.FAIL;
    }

    static void validate(ServerLevel level, FacilityDefinition facility, FacilityScripts.Await action) {
        String reading = action.reading();
        if (!facility.readings(level).containsKey(reading)) {
            throw new IllegalArgumentException("unknown reading " + reading);
        }
        signal(level, facility, reading).flatMap(signal -> signal.type().check(action.value())).ifPresent(reason -> {
            throw new IllegalArgumentException(reading + ": " + reason);
        });
    }

    private static Optional<Signal> signal(ServerLevel level, FacilityDefinition facility, String reading) {
        if (facility.probes().containsKey(reading) || !facility.controls().containsKey(reading)) {
            return Optional.empty();
        }
        return Controls.resolve(level, facility.ref(reading)).filter(Signal.class::isInstance).map(Signal.class::cast);
    }
}
