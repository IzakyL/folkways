package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Objects;

/**
 * The physical store behind a port. `key` decides sameness: two registered blocks whose ports share a key are one
 * store with several access points, such as the two halves of a double chest, a drawer controller and its drawers, or
 * every device on one storage network. `key` must use value equality and stay equal while the store stays whole.
 *
 * <p>`home` is the block the port was resolved at. The colony picks the canonical anchor among registered blocks that
 * share a key; ports never choose it.
 */
public record PortIdentity(Object key, WorldPos home) {

    public PortIdentity {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(home, "home");
    }

    /** A store that is only itself: one block, one store. */
    public static PortIdentity block(WorldPos at) {
        return new PortIdentity(new Block(at), at);
    }

    public record Block(WorldPos at) {
    }
}
