package io.github.izakyl.folkways.core.api.work;

import java.util.UUID;

public interface Workshop extends Refinement {

    UUID key();

    WorkSite site();
}
