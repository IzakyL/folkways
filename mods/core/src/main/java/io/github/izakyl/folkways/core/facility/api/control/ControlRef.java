package io.github.izakyl.folkways.core.facility.api.control;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

/** A control named by where it is and which part of the block it is; resolve it with {@link Controls#resolve}. */
public record ControlRef(WorldPos at, String part) {

    public ControlRef {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(part, "part");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("at", at.save());
        tag.putString("part", part);
        return tag;
    }

    public static Optional<ControlRef> load(CompoundTag tag) {
        return WorldPos.load(Reader.of(tag.getCompound("at"))).map(at -> new ControlRef(at, tag.getString("part")));
    }
}
