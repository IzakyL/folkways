package io.github.izakyl.folkways.core.api.work;

import java.util.UUID;

public interface Workshop extends Refinement {

    UUID key();

    WorkSite site();

    // Whether its offers for one ask are ways to the same goods that spoken for hold the same, and differ only in
    // where the work is done: the plan takes whichever suits the crew where it stands, and weighs that again until the
    // work is begun. A node that may still be swapped for another way says so by being replannable.
    default boolean alike() {
        return false;
    }

    // Whether its work moves the goods it is asked for rather than making them: what it needs is the very goods it
    // gives, somewhere else. Those are drawn only from stock or a pack, never made or moved again for it.
    default boolean carries() {
        return false;
    }
}
