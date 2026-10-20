package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.OwnerSave;
import io.github.izakyl.folkways.core.api.colony.OwnerSaves;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Keeps implements ColonyChangeSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");
    private static final String MARKER = "folkways:ownerSave";
    private static final String FORMAT = "folkways:owner-save/1";
    private static final String OWNER = "owner";
    private static final String VERSION = "version";
    private static final String PAYLOAD = "payload";
    private static final String ORIGINAL = "original";

    private final Map<ResourceLocation, CompoundTag> bags = new LinkedHashMap<>();
    private final Set<ResourceLocation> prepared = new HashSet<>();
    private final Set<ResourceLocation> blocked = new HashSet<>();

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
        if (OwnerSaves.of(owner).isPresent() || blocked.contains(owner)
                || bags.containsKey(owner) && marked(bags.get(owner))) {
            throw new IllegalStateException(owner + " needs its versioned save API; raw writes are refused");
        }
        if (bag.equals(bags.get(owner))) {
            return;
        }
        bags.put(owner, bag.copy());
        prepared.remove(owner);
        dirtyListener.run();
    }

    synchronized Optional<CompoundTag> kept(OwnerSave save) {
        declared(save);
        if (!allowed(save.owner())) {
            return Optional.empty();
        }
        CompoundTag bag = bags.get(save.owner());
        return Optional.of(bag == null ? new CompoundTag() : bag.getCompound(PAYLOAD).copy());
    }

    synchronized void keep(OwnerSave save, CompoundTag payload) {
        declared(save);
        if (!allowed(save.owner())) {
            throw new IllegalStateException(save.owner() + " saved data is protected; writes are refused");
        }
        CompoundTag validated = save.migrate(save.version(), payload);
        CompoundTag old = bags.get(save.owner());
        CompoundTag next = old == null ? envelope(save, validated) : old.copy();
        next.put(PAYLOAD, validated);
        if (!next.equals(old)) {
            bags.put(save.owner(), next);
            dirtyListener.run();
        }
    }

    private static void declared(OwnerSave save) {
        if (OwnerSaves.of(save.owner()).filter(save::equals).isEmpty()) {
            throw new IllegalArgumentException(save.owner() + " has not declared this save schema");
        }
    }

    synchronized boolean allowed(ResourceLocation owner) {
        if (!prepared.add(owner)) {
            return !blocked.contains(owner);
        }
        CompoundTag bag = bags.get(owner);
        if (bag == null) {
            return true;
        }
        Optional<OwnerSave> declared = OwnerSaves.of(owner);
        if (declared.isEmpty()) {
            if (marked(bag)) {
                return protect(owner, "its versioned save schema is unavailable", null);
            }
            return true;
        }
        OwnerSave save = declared.get();
        CompoundTag next;
        try {
            int from = 0;
            CompoundTag payload = bag;
            if (marked(bag)) {
                if (!bag.contains(MARKER, Tag.TAG_STRING) || !FORMAT.equals(bag.getString(MARKER))
                        || !bag.contains(OWNER, Tag.TAG_STRING) || !owner.toString().equals(bag.getString(OWNER))
                        || !bag.contains(VERSION, Tag.TAG_INT) || !bag.contains(PAYLOAD, Tag.TAG_COMPOUND)
                        || bag.contains(ORIGINAL) && !bag.contains(ORIGINAL, Tag.TAG_COMPOUND)) {
                    return protect(owner, "its save envelope is malformed", null);
                }
                from = bag.getInt(VERSION);
                payload = bag.getCompound(PAYLOAD);
                if (from < 0 || from > save.version()) {
                    return protect(owner, "unsupported save version " + from + ", current " + save.version(), null);
                }
                if (from == save.version()) {
                    save.migrate(from, payload);
                    return true;
                }
            }
            CompoundTag migrated = save.migrate(from, payload);
            next = marked(bag) ? bag.copy() : envelope(save, migrated);
            next.putInt(VERSION, save.version());
            next.put(PAYLOAD, migrated);
            if (!next.contains(ORIGINAL)) {
                next.put(ORIGINAL, bag.copy());
            }
        } catch (RuntimeException | Error broken) {
            if (broken instanceof VirtualMachineError fatal) {
                prepared.remove(owner);
                throw fatal;
            }
            if (broken instanceof ThreadDeath fatal) {
                prepared.remove(owner);
                throw fatal;
            }
            return protect(owner, "migration or validation failed", broken);
        }
        bags.put(owner, next);
        dirtyListener.run();
        return true;
    }

    private boolean protect(ResourceLocation owner, String reason, Throwable broken) {
        blocked.add(owner);
        LOGGER.error("Owner {} is disabled: {}; its original saved data is retained", owner, reason, broken);
        return false;
    }

    private static boolean marked(CompoundTag tag) {
        return tag.contains(MARKER);
    }

    private static CompoundTag envelope(OwnerSave save, CompoundTag payload) {
        return Writer.of().string(MARKER, FORMAT).id(OWNER, save.owner()).integer(VERSION, save.version())
            .blob(PAYLOAD, payload.copy()).tag();
    }

    synchronized void requireRelocatable() {
        for (ResourceLocation owner : bags.keySet()) {
            if (!allowed(owner)) {
                throw new IllegalStateException("Cannot relocate a colony while owner " + owner
                    + " has protected saved data");
            }
        }
    }

    synchronized boolean references(Set<WorldPos> moved) {
        return bags.values().stream().anyMatch(tag -> references(marked(tag) ? tag.getCompound(PAYLOAD) : tag, moved));
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
        if (moved.isEmpty()) {
            return;
        }
        requireRelocatable();
        Map<ResourceLocation, CompoundTag> next = new LinkedHashMap<>();
        bags.forEach((owner, bag) -> {
            if (marked(bag)) {
                CompoundTag envelope = bag.copy();
                envelope.put(PAYLOAD, remap(bag.getCompound(PAYLOAD), moved));
                next.put(owner, envelope);
            } else {
                next.put(owner, remap(bag, moved));
            }
        });
        if (!bags.equals(next)) {
            bags.clear();
            bags.putAll(next);
            dirtyListener.run();
        }
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
        prepared.clear();
        blocked.clear();
        dirtyListener.run();
    }

    synchronized CompoundTag save() {
        Writer writer = Writer.of();
        bags.forEach((owner, bag) -> writer.blob(owner.toString(), bag.copy()));
        return writer.tag();
    }

    synchronized void load(Reader reader) {
        bags.clear();
        prepared.clear();
        blocked.clear();
        for (String key : reader.keys()) {
            ResourceLocation owner = ResourceLocation.tryParse(key);
            if (owner != null) {
                reader.blob(key).ifPresent(bag -> bags.put(owner, bag.copy()));
            }
        }
    }
}
