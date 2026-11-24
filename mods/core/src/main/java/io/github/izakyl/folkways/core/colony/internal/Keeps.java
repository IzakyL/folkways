package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class Keeps implements ColonyChangeSource {

    private final Map<ResourceLocation, CompoundTag> bags = new LinkedHashMap<>();
    private final Map<ResourceLocation, Long> revisions = new LinkedHashMap<>();
    private long revision;

    public synchronized long revision(ResourceLocation owner) {
        return revisions.getOrDefault(owner, 0L);
    }

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
        CompoundTag previous = bags.get(owner);
        if (bag.equals(previous) || previous == null && bag.isEmpty()) {
            return;
        }
        bags.put(owner, bag.copy());
        revisions.put(owner, ++revision);
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
        bags.replaceAll((owner, bag) -> {
            revisions.put(owner, ++revision);
            return remap(bag, moved);
        });
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
        bags.keySet().forEach(owner -> revisions.put(owner, ++revision));
        bags.clear();
        dirtyListener.run();
    }

    synchronized CompoundTag save() {
        Writer writer = Writer.of();
        bags.forEach((owner, bag) -> writer.blob(owner.toString(), bag));
        return writer.tag();
    }

    synchronized void load(Reader reader) {
        bags.keySet().forEach(owner -> revisions.put(owner, ++revision));
        bags.clear();
        for (String key : reader.keys()) {
            ResourceLocation owner = ResourceLocation.parse(key);
            bags.put(owner, reader.blob(key).orElseThrow());
            revisions.put(owner, ++revision);
        }
    }
}
