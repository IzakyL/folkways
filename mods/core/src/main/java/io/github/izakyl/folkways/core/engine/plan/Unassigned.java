package io.github.izakyl.folkways.core.engine.plan;

import java.util.UUID;

public record Unassigned(UUID node, Reason why, UUID blockedBy) {

    // Why no one was given the work. Most only mean waiting: for a way to be found, a pack to be emptied, a hand to
    // come free. The structural ones mean no one could ever be given it, which the plan is never to grow - seeing
    // one is a bug in how the work was grown, not a state of the world.
    public enum Reason {

        NO_FREE_HAND,

        NO_LICENCE,

        NO_TOOL,

        NO_WAY,

        WAY_PENDING,

        PACK_FULL,

        PACK_BUSY,

        CLAIM_TAKEN,

        SAME_WORKER_SPLIT,

        WORKER_AWAY,

        NO_FREE_STAND,

        // Someone could take it, but no sooner than the plan looks ahead: it waits for a later plan, not for a
        // change in the world.
        BEYOND_HORIZON;

        public boolean structural() {
            return switch (this) {
                case NO_LICENCE, NO_TOOL, PACK_FULL, SAME_WORKER_SPLIT -> true;
                default -> false;
            };
        }
    }

    public Unassigned(UUID node, Reason why) {
        this(node, why, null);
    }
}
