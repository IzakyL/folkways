package io.github.izakyl.folkways.core.work.api;

public enum NodeState {
    PENDING, READY, WORKING, SETTLING, DONE, FAILED, CANCELLED;

    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
