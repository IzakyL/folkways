package io.github.izakyl.folkways.core.api.persist;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;

public final class Writer {
    private final CompoundTag tag;

    private Writer(CompoundTag tag) {
        this.tag = tag;
    }

    public static Writer of() {
        return new Writer(new CompoundTag());
    }

    public static Writer of(CompoundTag tag) {
        return new Writer(tag);
    }

    public CompoundTag tag() {
        return tag;
    }

    public Writer uuid(String key, UUID value) {
        tag.putUUID(key, value);
        return this;
    }

    public Writer uuid(String key, Optional<UUID> value) {
        value.ifPresent(held -> tag.putUUID(key, held));
        return this;
    }

    public Writer string(String key, String value) {
        tag.putString(key, value);
        return this;
    }

    public Writer id(String key, ResourceLocation value) {
        tag.putString(key, value.toString());
        return this;
    }

    public Writer id(String key, Optional<ResourceLocation> value) {
        value.ifPresent(held -> tag.putString(key, held.toString()));
        return this;
    }

    public Writer integer(String key, int value) {
        tag.putInt(key, value);
        return this;
    }

    public Writer decimal(String key, float value) {
        tag.putFloat(key, value);
        return this;
    }

    public Writer longValue(String key, long value) {
        tag.putLong(key, value);
        return this;
    }

    public Writer flag(String key, boolean value) {
        tag.putBoolean(key, value);
        return this;
    }

    public Writer blockPos(String key, BlockPos value) {
        tag.put(key, NbtUtils.writeBlockPos(value));
        return this;
    }

    public Writer intArray(String key, int[] value) {
        tag.putIntArray(key, value);
        return this;
    }

    public Writer ids(String key, Collection<ResourceLocation> values) {
        ListTag list = new ListTag();
        for (ResourceLocation value : values) {
            list.add(StringTag.valueOf(value.toString()));
        }
        tag.put(key, list);
        return this;
    }

    public Writer uuids(String key, Collection<UUID> values) {
        ListTag list = new ListTag();
        for (UUID value : values) {
            list.add(NbtUtils.createUUID(value));
        }
        tag.put(key, list);
        return this;
    }

    public Writer child(String key) {
        CompoundTag held = new CompoundTag();
        tag.put(key, held);
        return new Writer(held);
    }

    public <T> Writer children(String key, Collection<T> values, Function<T, CompoundTag> save) {
        ListTag list = new ListTag();
        for (T value : values) {
            list.add(save.apply(value));
        }
        tag.put(key, list);
        return this;
    }

    public Writer blob(String key, CompoundTag value) {
        tag.put(key, value);
        return this;
    }
}
