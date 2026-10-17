package io.github.izakyl.folkways.plugins.person.living;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class Watermark {

    private final int holdAt;
    private final int urgentAt;
    private final int restoreTo;

    private final Set<UUID> engaged = new HashSet<>();

    Watermark(int holdAt, int urgentAt, int restoreTo) {
        if (urgentAt >= restoreTo) {
            throw new IllegalArgumentException("a watermark restoring to " + restoreTo
                + " and urgent at " + urgentAt + " stays urgent on the restoration threshold forever");
        }
        if (urgentAt > holdAt || holdAt >= restoreTo) {
            throw new IllegalArgumentException("a watermark reads urgentAt <= holdAt < restoreTo, not "
                + urgentAt + " <= " + holdAt + " < " + restoreTo);
        }
        this.holdAt = holdAt;
        this.urgentAt = urgentAt;
        this.restoreTo = restoreTo;
    }

    boolean holds(UUID who, int reserve) {
        if (reserve >= restoreTo) {
            engaged.remove(who);
            return false;
        }
        if (reserve <= holdAt) {
            engaged.add(who);
            return true;
        }
        return engaged.contains(who);
    }

    boolean urgent(int reserve) {
        return reserve <= urgentAt;
    }

    void forget() {
        engaged.clear();
    }

    void retain(Set<UUID> who) {
        engaged.retainAll(who);
    }
}
