package io.github.izakyl.folkways.core.engine.labor;

import java.util.concurrent.Executor;

// Where a colony's planning runs: its weave on one lane, its schedule on the other. Inline, both run on the
// caller's thread, there and then.
record Lanes(Executor weave, Executor schedule) {

    static final Lanes INLINE = new Lanes(Runnable::run, Runnable::run);
}
