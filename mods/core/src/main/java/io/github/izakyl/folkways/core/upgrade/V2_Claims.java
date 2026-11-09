package io.github.izakyl.folkways.core.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Master 9a45a4d52 wrote the same claim records inside a bag named holdings. */
public final class V2_Claims implements Step {
    @Override
    public int target() {
        return 2;
    }

    @Override
    public Surface surface() {
        return Surface.COLONY_REGISTRY;
    }

    @Override
    public void apply(CompoundTag raw) {
        for (Tag value : raw.getList("colonies", Tag.TAG_COMPOUND)) {
            CompoundTag colony = (CompoundTag) value;
            if (colony.contains("holdings", Tag.TAG_COMPOUND) && !colony.contains("claims")) {
                colony.put("claims", colony.getCompound("holdings"));
                colony.remove("holdings");
            }
        }
    }
}
