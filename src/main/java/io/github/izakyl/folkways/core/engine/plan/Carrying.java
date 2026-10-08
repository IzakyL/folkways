package io.github.izakyl.folkways.core.engine.plan;

/**
 * The rule goods move by. A transfer is the intent on a material edge: goods to go from the source the plan chose for
 * them to the work the edge feeds, or into a store. The rule refines it into the work that moves them, and the flows
 * the goods take through that work. Goods that can be reached where they lie, that are already in the pack of
 * whoever does the work, or that are handed on as they are made need no work to move them: the edge is refined into
 * itself, a flow straight into the work, with what it binds the work at either end to.
 */
@FunctionalInterface
public interface Carrying {

    // How many of the goods it laid the way for, through what the plan lends it: as many as it could, or none. What it
    // laid for goods it could not carry it takes back itself.
    long lay(Transfer transfer, Laying plan);
}
