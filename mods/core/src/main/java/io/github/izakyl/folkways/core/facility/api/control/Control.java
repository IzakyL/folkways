package io.github.izakyl.folkways.core.facility.api.control;

import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * A {@link Signal} or {@link Port} reports facts about itself: its contents, its feedback, and why it cannot
 * operate. A control carries no meaning of its own. Meaning comes from the facility that uses it.
 *
 * <p>Controls are resolved afresh from the world by {@code Controls} whenever they are needed and are not kept
 * between ticks. Everything they promise holds only on the server thread.
 */
public sealed interface Control permits Signal, Port, Motion {

    /** Any workable footing beside the anchor, told in the anchor's realm, which may be a moving structure. */
    static Optional<Stances> reach(ServerLevel level, WorldPos anchor) {
        if (!anchor.in(level) || !level.isLoaded(anchor.block(level))) {
            return Optional.empty();
        }
        BlockPos block = anchor.block(level);
        // Footings are found in the level and told in the anchor's realm.
        return Stances.of(anchor.realm(), Reach.workableCells(level, block).stream()
            .map(cell -> cell.subtract(block).offset(anchor.cell())).toList());
    }

    /** The block, or the cell an entity was seen in, that this control belongs to. */
    WorldPos anchor();

    /**
     * Which control of the block this is, for blocks that have several. Stable while
     * the block stays: it names the control in facility data.
     */
    String part();

    /** The moving entity carrying this control, if any. */
    default Optional<UUID> entityId() {
        return Optional.empty();
    }

    /** Additional native readings keyed by their suffix. */
    default java.util.Map<String, Reading> readings() {
        return java.util.Map.of();
    }

    /** How a facility names this control. */
    default ControlRef ref() {
        return new ControlRef(anchor(), part());
    }

    /**
     * What a {@code Lease} is exclusive on. Value equality; controls sharing a key are one place to stand. A control on
     * an entity that moves keys on the entity, not on where it was seen.
     */
    default Object leaseKey() {
        return ref();
    }

    /** The reason the control cannot operate, when the adapter can report one. */
    default Optional<Component> unavailable(ServerLevel level) {
        return Optional.empty();
    }

    /** Whether residents may write to it now. Overrides must also honor any reported unavailability. */
    default boolean valid(ServerLevel level) {
        return anchor().in(level) && level.isLoaded(anchor().block(level)) && unavailable(level).isEmpty();
    }

    /**
     * Where the writer must be, in the engine's terms: footings, a seat ({@link Stances.Seat}, sat in on arrival), or
     * anywhere. Empty when it has nowhere to be written from now. By default, any workable footing beside the anchor.
     */
    default Optional<Stances> stance(ServerLevel level) {
        return reach(level, anchor());
    }

    /** Whether this body may write the control at all; asked on arrival, before it sits in a seat stance. */
    default boolean admits(Mob writer) {
        return true;
    }

    /** What one write costs the writer. */
    default Cost cost() {
        return Cost.INSTANT;
    }

    /**
     * Called with the lease taken, the writer already at its stance; false refuses the lease. A control that must
     * ready itself for its writer does it here. A lease never seats anyone.
     */
    default boolean enter(Mob writer) {
        return true;
    }

    /**
     * Called once when a lease ends, however it ends, if {@link #enter} was called for it: also when enter refused or
     * threw, so an enter that fails halfway leaves its undoing here.
     */
    default void leave(Mob writer) {
    }

    /** What one write costs: nothing, a fixed wind-up, or labour until the reading shows the target (a crank). */
    sealed interface Cost {

        Instant INSTANT = new Instant();
        Remote REMOTE = new Remote();

        /** The native control can execute a write without an on-site writer. */
        record Remote() implements Cost {
        }

        record Instant() implements Cost {
        }

        record Ticks(int ticks) implements Cost {

            public Ticks {
                if (ticks <= 0) {
                    throw new IllegalArgumentException("a wind-up takes ticks, not " + ticks);
                }
            }
        }

        record UntilRead() implements Cost {
        }
    }
}
