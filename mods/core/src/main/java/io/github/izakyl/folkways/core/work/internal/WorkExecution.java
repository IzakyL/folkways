package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.StandardHoldings;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

// Bound once by the runtime; sessions share execution state and report changes without consulting the planner.
public record WorkExecution(Colony colony, Phases phases, Phases personal, HoldingRegistry holdings,
                            BiFunction<Body, Placement, Worker> hands, HoldingActions actions, Consumer<Event> report) {

    public sealed interface Event {
        record Started(UUID node, UUID resident) implements Event { }
        record Failed(UUID node, RefusalKind why) implements Event { }
        record Cancelled(Node node) implements Event { }
        record Released(UUID node) implements Event { }
        record Forgotten(UUID node) implements Event { }
        record Gone(UUID resident) implements Event { }
        record Read(UUID resident) implements Event { }
        record Refused(ResourceLocation urge, RefusalKind why, long now) implements Event { }
        record Balked(Node node, RefusalKind why, long now) implements Event { }
    }

    public Worker hands(Body body, Placement placement) {
        return hands.apply(body, placement);
    }

    public HoldingContext context(ServerLevel level, Body body, Placement placement) {
        Worker worker = hands(body, placement);
        return new HoldingContext(level, body, worker, actions, new HoldingSettlement(level, worker),
            StandardHoldings.INSTANCE);
    }

    public void report(Event event) {
        report.accept(event);
    }
}
