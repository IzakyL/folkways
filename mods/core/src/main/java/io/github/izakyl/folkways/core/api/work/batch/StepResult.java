package io.github.izakyl.folkways.core.api.work.batch;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Node;
import java.util.Objects;

public sealed interface StepResult {
    // A stable logical key, with a fresh node id for every execution attempt.
    record Step(String key, Node node) implements StepResult {
        public Step { Objects.requireNonNull(key); Objects.requireNonNull(node); }
    }

    record Wait(int ticks) implements StepResult {
        public Wait { if (ticks < 1) throw new IllegalArgumentException("wait must take time"); }
    }

    // A scoped inventory transfer (for example borrowing a tool), outside the production input budget.
    // Requires InventoryTransfer and no production declaration; emits no production receipt.
    record Auxiliary(Node node) implements StepResult {
        public Auxiliary { Objects.requireNonNull(node); }
    }

    // Cooperatively pause this runner; retain the body and parent allocation to avoid re-provisioning spent inputs.
    record Pause(int ticks) implements StepResult {
        public Pause { if (ticks < 1) throw new IllegalArgumentException("pause must take time"); }
    }

    record Complete() implements StepResult { }

    record Failed(RefusalKind why) implements StepResult {
        public Failed { Objects.requireNonNull(why); }
    }
}
