package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class Keeps implements ColonyChangeSource {

    private final Map<ResourceLocation, CompoundTag> bags = new LinkedHashMap<>();

    private Runnable dirtyListener = () -> {
    };

    @Override
    public synchronized void setDirtyListener(Runnable dirtyListener) {
        this.dirtyListener = dirtyListener;
    }

    public synchronized CompoundTag kept(ResourceLocation owner) {
        CompoundTag bag = bags.get(owner);
        return bag == null ? new CompoundTag() : bag.copy();
    }

    public synchronized void keep(ResourceLocation owner, CompoundTag bag) {
        bags.put(owner, bag.copy());
        dirtyListener.run();
    }

    synchronized boolean references(Set<WorldPos> moved) {
        return bags.values().stream().anyMatch(tag -> references(tag, moved));
    }

    private static boolean references(Tag tag, Set<WorldPos> moved) {
        if (tag instanceof CompoundTag compound) {
            if (WorldPos.load(Reader.of(compound)).filter(moved::contains).isPresent()) {
                return true;
            }
            return compound.getAllKeys().stream().anyMatch(key -> references(compound.get(key), moved));
        }
        if (tag instanceof ListTag list) {
            return list.stream().anyMatch(child -> references(child, moved));
        }
        return false;
    }

    synchronized void relocate(Map<WorldPos, WorldPos> moved) {
        bags.replaceAll((owner, bag) -> remap(bag, moved));
        dirtyListener.run();
    }

    private static CompoundTag remap(CompoundTag tag,
            Map<WorldPos, WorldPos> moved) {
        var address = WorldPos.load(Reader.of(tag));
        if (address.isPresent() && moved.containsKey(address.get())) {
            CompoundTag changed = tag.copy();
            CompoundTag location = moved.get(address.get()).save();
            location.getAllKeys().forEach(key -> changed.put(key, location.get(key).copy()));
            return changed;
        }
        CompoundTag copy = tag.copy();
        for (String key : tag.getAllKeys()) {
            copy.put(key, remapValue(tag.get(key), moved));
        }
        return copy;
    }

    private static Tag remapValue(Tag value, Map<WorldPos, WorldPos> moved) {
        if (value instanceof CompoundTag child) {
            return remap(child, moved);
        }
        if (value instanceof ListTag list) {
            ListTag next = new ListTag();
            list.forEach(entry -> next.add(remapValue(entry, moved)));
            return next;
        }
        return value.copy();
    }

    synchronized void clear() {
        bags.clear();
        dirtyListener.run();
    }

    synchronized CompoundTag save() {
        Writer writer = Writer.of();
        bags.forEach((owner, bag) -> writer.blob(owner.toString(), bag));
        return writer.tag();
    }

    synchronized void load(Reader reader) {
        bags.clear();
        for (String key : reader.keys()) {
            ResourceLocation owner = ResourceLocation.tryParse(key);
            if (owner != null) {
                reader.blob(key).ifPresent(bag -> bags.put(owner, bag));
            }
        }
    }
}
