package io.github.izakyl.folkways.core.api.work;

// What a node's owner wants done when work it is ordered after ends without being done: it failed, or was given up
// or withdrawn. Goods the node needs the plan feeds again some other way; an order is the owner's own, so the owner
// says what is left of it.
public enum Unmet {

    // The order was only a turn to take: it goes, and the node is done whenever the rest of what it comes after is.
    GO_ON,

    // The node is kept off the plan until that work is asked for again and done, or the node itself is withdrawn.
    WAIT,

    // The node fails too, and what is ordered after it is asked in turn. What every node answers unless it says
    // otherwise.
    FAIL
}
