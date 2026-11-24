package io.github.izakyl.folkways.core.world.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Fresh identities for a copy of part of a world. Every UUID in a copied tag (an int array of four, a UUID string, or a
 * {@code ...Most}/{@code ...Least} pair) is replaced by a fresh one, the same original always by the same fresh one, so
 * the copy refers only to itself: a sub-level, a walker or a link keeps pointing at its copied partner, and nothing in
 * the copy names anything real.
 */
public final class CopiedIds {

    private final Map<UUID, UUID> fresh = new HashMap<>();
    private final Map<UUID, UUID> original = new HashMap<>();

    /** The copy's identity for an original. */
    public UUID of(UUID id) {
        return fresh.computeIfAbsent(id, key -> {
            UUID made = UUID.randomUUID();
            original.put(made, key);
            return made;
        });
    }

    /** The original a copy's identity stands for. */
    public Optional<UUID> original(UUID copy) {
        return Optional.ofNullable(original.get(copy));
    }

    /** A copy of the tag with every UUID in it replaced. */
    public CompoundTag retag(CompoundTag tag) {
        return (CompoundTag) retag((Tag) tag);
    }

    private Tag retag(Tag tag) {
        return switch (tag) {
            case CompoundTag compound -> {
                CompoundTag copy = new CompoundTag();
                for (String key : compound.getAllKeys()) {
                    copy.put(key, retag(compound.get(key)));
                }
                for (String key : List.copyOf(copy.getAllKeys())) {
                    if (key.endsWith("Most") && copy.get(key) instanceof LongTag most
                            && copy.get(key.substring(0, key.length() - 4) + "Least") instanceof LongTag least) {
                        UUID mapped = of(new UUID(most.getAsLong(), least.getAsLong()));
                        copy.putLong(key, mapped.getMostSignificantBits());
                        copy.putLong(key.substring(0, key.length() - 4) + "Least", mapped.getLeastSignificantBits());
                    }
                }
                yield copy;
            }
            case ListTag list -> {
                ListTag copy = new ListTag();
                list.forEach(element -> copy.add(retag(element)));
                yield copy;
            }
            case IntArrayTag array when array.size() == 4 -> NbtUtils.createUUID(of(NbtUtils.loadUUID(array)));
            case StringTag text -> uuid(text.getAsString()).map(id -> (Tag) StringTag.valueOf(of(id).toString()))
                .orElse(text);
            default -> tag.copy();
        };
    }

    private static Optional<UUID> uuid(String text) {
        if (text.length() != 36 || text.charAt(8) != '-' || text.charAt(13) != '-' || text.charAt(18) != '-'
                || text.charAt(23) != '-') {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notOne) {
            return Optional.empty();
        }
    }
}
