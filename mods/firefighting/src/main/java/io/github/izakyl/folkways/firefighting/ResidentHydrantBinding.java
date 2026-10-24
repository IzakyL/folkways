package io.github.izakyl.folkways.firefighting;

import java.util.UUID;

// The cabinet's own binding is shared with players; resident sessions never take a second connection beside it.
public interface ResidentHydrantBinding {
    boolean folkways$bindResident(UUID resident);

    void folkways$unbindResident(UUID resident, boolean notify);
}
