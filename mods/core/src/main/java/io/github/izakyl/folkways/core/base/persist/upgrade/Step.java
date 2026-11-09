package io.github.izakyl.folkways.core.base.persist.upgrade;

import net.minecraft.nbt.CompoundTag;

/** Rewrites raw data from target - 1 to target, before current business readers run. */
public interface Step {
    int target();

    Surface surface();

    void apply(CompoundTag raw);
}
