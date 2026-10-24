package io.github.izakyl.folkways.core.api.structure;

import net.minecraft.nbt.CompoundTag;

/** A structure cell's configuration, without a live block entity or inventory. */
public record StructurePayload(CompoundTag tag) {
    public static final StructurePayload EMPTY = new StructurePayload(new CompoundTag());

    public StructurePayload {
        tag = tag.copy();
    }

    @Override
    public CompoundTag tag() {
        return tag.copy();
    }

    public boolean present() {
        return !tag.isEmpty();
    }
}
