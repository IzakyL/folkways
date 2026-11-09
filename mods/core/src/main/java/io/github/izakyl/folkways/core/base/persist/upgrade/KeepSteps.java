package io.github.izakyl.folkways.core.base.persist.upgrade;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Literal registry layout lives here so plugin migrations only need their own bag's keys. */
public final class KeepSteps {
    private KeepSteps() {
    }

    /** Bag contributions are composed after the registry step at the same target, in owner-id order. */
    public static void bag(String owner, int target, Consumer<CompoundTag> change) {
        Upgrades.registerBag(owner, target, change);
    }

    static void apply(CompoundTag raw, String owner, Consumer<CompoundTag> change) {
        for (Tag value : raw.getList("colonies", Tag.TAG_COMPOUND)) {
            CompoundTag keeps = ((CompoundTag) value).getCompound("keeps");
            if (keeps.contains(owner, Tag.TAG_COMPOUND)) {
                change.accept(keeps.getCompound(owner));
            }
        }
    }
}
