package io.github.izakyl.folkways.core.world.api;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import net.minecraft.nbt.CompoundTag;

/** A structure cell's configuration, without a live block entity or inventory. */
public record StructurePayload(CompoundTag tag) {
    public static final int MAX_PREVIEW_BYTES = 256 * 1024;

    public static final StructurePayload EMPTY = new StructurePayload(new CompoundTag());

    public StructurePayload {
        tag = tag.copy();
        // Exported files have no world data version and can be imported long after a world was upgraded.
        NamespaceIds.rewrite(tag);
    }

    @Override
    public CompoundTag tag() {
        return tag.copy();
    }

    public boolean present() {
        return !tag.isEmpty();
    }

    public int bytes() {
        return tag.sizeInBytes();
    }
}
