package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;

// Asked work the plan gave up, and how: it ends so for its owner, whether or not it was ever on the plan.
public record Gone(Node node, Ending how) {
}
