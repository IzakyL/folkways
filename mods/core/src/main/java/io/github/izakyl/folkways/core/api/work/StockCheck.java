package io.github.izakyl.folkways.core.api.work;

/**
 * A non-consuming inventory target. Needs reserve known stock wherever it already lies, without moving it,
 * assigning its carrier to this work, or promising that its space will become free. Missing stock may still
 * grow ordinary workshop production; products must be put into a real store before this check runs.
 *
 * Implementations must freshly validate their inventory scope in both draw and commit, including unknown
 * inventory and other consumers' obligations. Draw/restore exchange no stacks, and commit produces no goods,
 * unused goods or XP. These observations are not exported as consumable ReservedInputs.
 * Checks must be finite Once work with no tools, and must not be wrapped in tool or clothing work:
 * such decorators execute separate work and would lose the observation protocol at the planner boundary.
 */
public interface StockCheck extends Staged {
    static boolean valid(Node node) {
        return node.spec().needs().stream().noneMatch(Need::carried)
            && node.spec().gives().isEmpty() && node.spec().leaves().isEmpty()
            && node.spec().workload() instanceof Workload.Once && node.spec().tools().isEmpty()
            && node.planningLeaves(java.util.List.of()).isEmpty()
            && !(node instanceof io.github.izakyl.folkways.core.api.work.batch.BatchAction);
    }
}
