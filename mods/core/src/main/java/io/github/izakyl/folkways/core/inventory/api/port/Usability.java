package io.github.izakyl.folkways.core.inventory.api.port;

import java.util.Objects;
import net.minecraft.network.chat.Component;

/**
 * Whether residents may use a store. An unusable store is neither taken from nor put into, and its goods do not count
 * toward orders or coverage; the colony panel still lists it, greyed, with the reason.
 */
public sealed interface Usability {

    Usable USABLE = new Usable();

    record Usable() implements Usability {
    }

    /**
     * `lasting` marks a verdict found by watching the store misbehave rather than by reading its setup: it stays until
     * a player registers the block again. A verdict read from the setup (an installed void upgrade, a network without
     * power) is re-read on every observation and lifts by itself.
     */
    record Unusable(Component reason, boolean lasting) implements Usability {
        public Unusable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static Usability because(Component reason) {
        return new Unusable(reason, false);
    }

    default boolean usable() {
        return this instanceof Usable;
    }
}
