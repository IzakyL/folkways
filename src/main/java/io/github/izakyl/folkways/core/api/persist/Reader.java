package io.github.izakyl.folkways.core.api.persist;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class Reader {
    private final CompoundTag tag;

    private Reader(CompoundTag tag) {
        this.tag = tag;
    }

    public static Reader of(CompoundTag tag) {
        return new Reader(tag);
    }

    public Optional<UUID> uuid(String key) {
        return tag.hasUUID(key) ? Optional.of(tag.getUUID(key)) : Optional.empty();
    }

    public Optional<String> string(String key) {
        return tag.contains(key, Tag.TAG_STRING) ? Optional.of(tag.getString(key)) : Optional.empty();
    }

    public Optional<ResourceLocation> id(String key) {
        return string(key).map(ResourceLocation::tryParse);
    }

    public Optional<Integer> integer(String key) {
        return tag.contains(key, Tag.TAG_INT) ? Optional.of(tag.getInt(key)) : Optional.empty();
    }

    public Optional<Float> decimal(String key) {
        return tag.contains(key, Tag.TAG_FLOAT) ? Optional.of(tag.getFloat(key)) : Optional.empty();
    }

    public Optional<Long> longValue(String key) {
        return tag.contains(key, Tag.TAG_LONG) ? Optional.of(tag.getLong(key)) : Optional.empty();
    }

    public Optional<Boolean> flag(String key) {
        return tag.contains(key, Tag.TAG_BYTE) ? Optional.of(tag.getBoolean(key)) : Optional.empty();
    }

    public Optional<BlockPos> blockPos(String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) ? NbtUtils.readBlockPos(tag, key) : Optional.empty();
    }

    public Optional<int[]> intArray(String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) ? Optional.of(tag.getIntArray(key)) : Optional.empty();
    }

    public List<ResourceLocation> ids(String key) {
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<ResourceLocation> ids = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation parsed = ResourceLocation.tryParse(list.getString(i));
            if (parsed != null) {
                ids.add(parsed);
            }
        }
        return ids;
    }

    public List<UUID> uuids(String key) {
        ListTag list = tag.getList(key, Tag.TAG_INT_ARRAY);
        List<UUID> ids = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ids.add(NbtUtils.loadUUID(list.get(i)));
        }
        return ids;
    }

    public Optional<Reader> child(String key) {
        return tag.contains(key, Tag.TAG_COMPOUND) ? Optional.of(new Reader(tag.getCompound(key))) : Optional.empty();
    }

    public List<Reader> children(String key) {
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        List<Reader> readers = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            readers.add(new Reader(list.getCompound(i)));
        }
        return readers;
    }

    public Optional<CompoundTag> blob(String key) {
        return tag.contains(key, Tag.TAG_COMPOUND) ? Optional.of(tag.getCompound(key)) : Optional.empty();
    }

    public Set<String> keys() {
        return tag.getAllKeys();
    }
}
