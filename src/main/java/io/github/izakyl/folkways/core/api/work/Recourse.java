package io.github.izakyl.folkways.core.api.work;

// What a node's owner wants done when its work fails. Failing ends the node, and with it the growth it came from:
// whatever that growth fed is fed again from somewhere else, and work that fed nothing - what was asked - is given
// up. Waiting keeps the node on the graph, to be tried again once the ticks are up: for a hitch that passes, like a
// slot someone else fills.
public sealed interface Recourse {

    record Fail() implements Recourse {
    }

    record Wait(int ticks) implements Recourse {

        public Wait {
            if (ticks <= 0) {
                throw new IllegalArgumentException("a wait needs ticks, not " + ticks);
            }
        }
    }

    Recourse FAIL = new Fail();

    static Recourse waitFor(int ticks) {
        return new Wait(ticks);
    }
}
