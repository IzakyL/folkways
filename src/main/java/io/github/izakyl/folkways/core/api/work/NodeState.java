package io.github.izakyl.folkways.core.api.work;

public enum NodeState {
    PENDING, READY, WORKING, SETTLING, DONE, FAILED, CANCELLED;

    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
