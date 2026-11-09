package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

public interface Nook {

    int width();

    Optional<Body> subject();

    CompoundTag kept();

    void keep(CompoundTag kept);
}
