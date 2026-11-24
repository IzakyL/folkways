package io.github.izakyl.folkways.core.facility.api.control;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * One thing the colony can read about the world without anyone going there: a block's power, a bearing's angle, a
 * tank's level. Readings are taken on the server thread; routines plan from a snapshot of them.
 */
@FunctionalInterface
public interface Reading {

    /** Empty while there is nothing to read: the block is unloaded or gone. */
    Optional<Value> read(ServerLevel level);
}
