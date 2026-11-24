package io.github.izakyl.folkways.core.facility.api.control;

/** A range of physical slot indices, with an exclusive upper bound. Disjoint selections are empty. */
public record SlotWindow(int from, int to) {

    public boolean contains(int slot) {
        return slot >= from && slot < to;
    }

    public int accessibleSlot(int slot) {
        return contains(slot) ? slot - from : -1;
    }
}
