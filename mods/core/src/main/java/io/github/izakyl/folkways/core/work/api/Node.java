package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

public interface Node extends WorkAction {

    @Override
    NodeSpec spec();

    // An enclosing work area may accept arrival before the planned stance is reached.
    default boolean arrived(ServerLevel level, Body body, Stances positions, boolean atStance) {
        return atStance;
    }

    default WorkHolding acquire(HoldingContext context) {
        return context.holdings().standard(context, this);
    }

    // Additional pack space beyond outputs already promised as carried or placed in a store. Nodes which estimate
    // their entire native output may deduct those promises; additive estimates, such as crafting remainders,
    // stay additive.
    default List<Amount> planningLeaves(List<Amount> promised) {
        return spec().leaves();
    }

    // Products may already be in the worker's pack while the node is still working.
    default boolean producesBeforeSettlement() {
        return false;
    }

    default Optional<UUID> worker() {
        return Optional.empty();
    }

    default Optional<RefusalKind> planned(ServerLevel level) {
        return Optional.empty();
    }

    // A reusable input already owned but temporarily unavailable must not trigger another manufacture.
    default boolean permitsProduction(io.github.izakyl.folkways.core.item.api.ItemSpec input) {
        return true;
    }

    default boolean acceptsInput(Stash from) {
        return true;
    }

    default boolean replannable() {
        return false;
    }

    // Whether starting this work can retain the resident's mount.
    default boolean mountedWork() {
        return false;
    }

    default boolean ready(ServerLevel level) {
        return true;
    }

    // Work that goes on in the same wind-up as the work just done, like reaching into the same store again. The
    // plan still sees each node as its own step; the worker only winds up for the first of such a run.
    default boolean joins(Node done) {
        return false;
    }

    // A pure hint: querying batching must not reserve resources or poll work readiness.
    default Optional<WorkBatch> batch(Body body) {
        return Optional.empty();
    }

    default boolean settled(ServerLevel level) {
        return true;
    }

    default void released(ServerLevel level, Worker who, Ending how) {
    }

    default void changed(ServerLevel level, NodeState state) {
    }

    default void ended(ServerLevel level, Ending how) {
    }

    default Optional<RefusalKind> refusal(ServerLevel level) {
        return Optional.empty();
    }

    // Asked when the node's work fails, whether by its commit, its refusal or the worker's: the owner says whether
    // the node fails, or waits and is tried again.
    default Recourse failing(ServerLevel level, RefusalKind why) {
        return Recourse.FAIL;
    }

    // Asked by the plan, off the server thread, when work this node was asked to come after ends without being done.
    // An order is a need of its own: what comes after work that was not done fails with it, unless its owner says
    // the order was only a turn to take.
    default Unmet unmet(UUID before, Ending how) {
        return Unmet.FAIL;
    }

    default UUID id() {
        return spec().id();
    }
}
