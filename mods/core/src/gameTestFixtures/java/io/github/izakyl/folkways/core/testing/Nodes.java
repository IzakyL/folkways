package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import net.minecraft.server.level.ServerLevel;

public final class Nodes {
    private Nodes() {
    }

    // Work that is done the moment it is committed, and otherwise keeps every default: two of the same spec are equal.
    public static Node done(NodeSpec spec) {
        return new Done(spec);
    }

    private record Done(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }
    }
}
