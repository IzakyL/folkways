package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

public interface Nook {

    int width();

    Optional<Body> subject();

    CompoundTag kept();

    void keep(CompoundTag kept);
}
