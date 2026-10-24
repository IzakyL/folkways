package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

public interface Node {

    NodeSpec spec();

    default Doing.Wares wares() {
        return spec().wares();
    }

    // Additional pack space beyond outputs already promised by the plan. Nodes which estimate their entire
    // native output may deduct those promises; additive estimates, such as crafting remainders, stay additive.
    default List<Amount> planningLeaves(List<Amount> promised) {
        return spec().leaves();
    }

    default Optional<UUID> worker() {
        return Optional.empty();
    }

    default Optional<RefusalKind> planned(ServerLevel level) {
        return Optional.empty();
    }

    // A reusable input already owned but temporarily unavailable must not trigger another manufacture.
    default boolean permitsProduction(io.github.izakyl.folkways.core.api.terms.ItemSpec input) {
        return true;
    }

    default boolean acceptsInput(Stash from) {
        return true;
    }

    default boolean replannable() {
        return false;
    }

    // Short local actions can use their locomotion directly instead of planning a multimodal journey.
    default boolean directTravel() {
        return false;
    }

    default boolean ready(ServerLevel level) {
        return true;
    }

    Outcome commit(ServerLevel level, Worker who);

    // Work that goes on in the same wind-up as the work just done, like reaching into the same store again. The
    // plan still sees each node as its own step; the worker only winds up for the first of such a run.
    default boolean joins(Node done) {
        return false;
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
