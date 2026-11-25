package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/** One idempotent operation on a facility, with a world reading for both readiness and settlement. */
public record EnsureState(Predicate<ServerLevel> satisfied, Predicate<ServerLevel> available,
                          BiFunction<ServerLevel, Worker, Outcome> apply) {

    public Readiness readiness(ServerLevel level) {
        return Readiness.of(satisfied.test(level) || available.test(level));
    }

    public Outcome commit(ServerLevel level, Worker who) {
        return satisfied.test(level) ? Outcome.done() : apply.apply(level, who);
    }
}
