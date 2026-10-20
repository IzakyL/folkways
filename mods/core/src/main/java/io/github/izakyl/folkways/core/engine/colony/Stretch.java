package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.base.task.Cleanup;
import io.github.izakyl.folkways.core.api.colony.Closing;
import java.util.ArrayList;
import java.util.List;

abstract class Stretch {

    private final List<Stretch> inner = new ArrayList<>();

    private Stretch outer;

    private boolean closing;

    final <S extends Stretch> S opened(S nested) {
        ((Stretch) nested).outer = this;
        inner.add(nested);
        return nested;
    }

    final void closed(Closing why) {
        if (closing) {
            return;
        }
        closing = true;
        try (Cleanup cleanup = new Cleanup("Closing " + getClass().getName() + " as " + why)) {
            try {
                for (Stretch nested : List.copyOf(inner)) {
                    cleanup.run("nested " + nested.getClass().getName(), () -> nested.closed(why));
                }
            } finally {
                inner.clear();
                if (outer != null) {
                    outer.inner.remove(this);
                    outer = null;
                }
                cleanup.run("ended", () -> ended(why));
            }
        }
    }

    final boolean over() {
        return closing;
    }

    protected abstract void ended(Closing why);
}
