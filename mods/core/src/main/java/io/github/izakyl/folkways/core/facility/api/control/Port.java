package io.github.izakyl.folkways.core.facility.api.control;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * A control describing resource transfer: the resource kind, what can be deposited or drawn, and any visible
 * contents or in-progress batch.
 *
 * <p>Facility routines lease controls for their writes. Colony store transfers coordinate through stock
 * accounting and can share a physical port with a facility routine.
 */
public non-sealed interface Port<R> extends Control {

    ResourceKind<R> resource();

    /** Physical identity. Ports with equal identities are views of the same goods. */
    default PortIdentity identity() {
        return PortIdentity.block(anchor());
    }

    /** Native effects of moving goods through the port. */
    default List<TransformHint> transforms() {
        return List.of();
    }

    /** Transfer operations the port supports. */
    default Set<Operation> operations() {
        return Set.of(Operation.TAKE, Operation.PUT);
    }

    /**
     * A cheap number that changes whenever the goods may have changed, when the port can tell. Empty means
     * observers rely on block-entity change events and {@code Ports.changed} instead.
     */
    default OptionalLong revision() {
        return OptionalLong.empty();
    }

    enum Operation {
        TAKE,
        PUT
    }

    /** A snapshot of visible contents, when the adapter can observe its buffer without transferring goods. */
    default List<R> contents(ServerLevel level) {
        return List.of();
    }

    /** A batch accepted by the facility, currently processing outside the visible buffers. */
    default Optional<InProgress<R>> inProgress(ServerLevel level) {
        return Optional.empty();
    }

    /** Starts any native preparation needed before an actual deposit. Call while holding the port lease. */
    default void prepareDeposit(ServerLevel level, Mob writer, R offered) {
    }

    /**
     * Puts in as much of `offered` as the port takes, by `writer`, and answers what it took, now: what the block does
     * with it afterwards is its own working. With `simulate`, nothing changes and the answer is what the real call
     * would take now. The port applies native transfer restrictions.
     */
    R deposit(ServerLevel level, Mob writer, R offered, boolean simulate);

    /** Takes out up to `wanted`, by `writer`, and answers what came out. With `simulate`, nothing changes. */
    R draw(ServerLevel level, Mob writer, R wanted, boolean simulate);

    @Override
    default String part() {
        return resource().id().toString();
    }
}
