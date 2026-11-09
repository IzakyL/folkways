package io.github.izakyl.folkways.core.base.persist;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Reads fields the current writer always emits; absence or a wrong type is malformed data. */
public final class Required {
    private Required() {
    }

    private static CompoundTag field(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("Missing or invalid persisted field '" + key + "' (type " + type + ")");
        }
        return tag;
    }

    public static int integer(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_INT).getInt(key);
    }

    public static long longValue(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_LONG).getLong(key);
    }

    public static double doubleValue(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_DOUBLE).getDouble(key);
    }

    public static byte byteValue(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_BYTE).getByte(key);
    }

    public static boolean flag(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_BYTE).getBoolean(key);
    }

    public static String string(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_STRING).getString(key);
    }

    public static UUID uuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) {
            throw new IllegalArgumentException("Missing or invalid persisted UUID '" + key + "'");
        }
        return tag.getUUID(key);
    }

    public static CompoundTag compound(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_COMPOUND).getCompound(key);
    }

    public static int[] intArray(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_INT_ARRAY).getIntArray(key);
    }

    public static long[] longArray(CompoundTag tag, String key) {
        return field(tag, key, Tag.TAG_LONG_ARRAY).getLongArray(key);
    }

    public static ListTag list(CompoundTag tag, String key, int elementType) {
        Tag value = field(tag, key, Tag.TAG_LIST).get(key);
        ListTag list = (ListTag) value;
        if (!list.isEmpty() && list.getElementType() != elementType) {
            throw new IllegalArgumentException("Invalid persisted list element type for '" + key + "'");
        }
        return list;
    }
}
