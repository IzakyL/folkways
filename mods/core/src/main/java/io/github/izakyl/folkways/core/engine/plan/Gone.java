package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;

// Asked work the plan gave up, and how: it ends so for its owner, whether or not it was ever on the plan.
public record Gone(Node node, Ending how) {
}
