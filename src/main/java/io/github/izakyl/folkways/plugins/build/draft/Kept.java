package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;

final class Kept {

    static final int MOST_VALUES = 4096;

    private static final String ITEM = "v";

    private Kept() {
    }

    static CompoundTag of(Dict<?, ?> kept) throws EvalException {
        int[] count = {0};
        return dict(kept, count);
    }

    private static CompoundTag dict(Dict<?, ?> kept, int[] count) throws EvalException {
        CompoundTag written = new CompoundTag();
        for (Map.Entry<?, ?> entry : kept.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw Starlark.errorf("what a pattern keeps is keyed by strings, got %s",
                    Starlark.type(entry.getKey()));
            }
            written.put(key, value(entry.getValue(), count));
        }
        return written;
    }

    private static Tag value(Object value, int[] count) throws EvalException {
        if (++count[0] > MOST_VALUES) {
            throw Starlark.errorf("a pattern keeps at most %d values from one round to the next", MOST_VALUES);
        }
        if (value instanceof Boolean flag) {
            return ByteTag.valueOf(flag);
        }
        if (value instanceof StarlarkInt whole) {
            return LongTag.valueOf(whole.toLong("kept number"));
        }
        if (value instanceof StarlarkFloat part) {
            return DoubleTag.valueOf(part.toDouble());
        }
        if (value instanceof String text) {
            return StringTag.valueOf(text);
        }
        if (value instanceof Dict<?, ?> nested) {
            return dict(nested, count);
        }
        if (value instanceof Sequence<?> many) {
            ListTag list = new ListTag();
            for (Object each : many) {
                CompoundTag wrapped = new CompoundTag();
                wrapped.put(ITEM, value(each, count));
                list.add(wrapped);
            }
            return list;
        }
        throw Starlark.errorf("a pattern keeps booleans, numbers, strings, lists and dicts, not %s",
            Starlark.type(value));
    }

    static Dict<String, Object> read(CompoundTag kept) {
        Map<String, Object> read = new LinkedHashMap<>();
        for (String key : kept.getAllKeys()) {
            read.put(key, starlark(kept.get(key)));
        }
        return Dict.immutableCopyOf(read);
    }

    private static Object starlark(Tag tag) {
        return switch (tag) {
            case ByteTag flag -> flag.getAsByte() != 0;
            case LongTag whole -> StarlarkInt.of(whole.getAsLong());
            case DoubleTag part -> StarlarkFloat.of(part.getAsDouble());
            case StringTag text -> text.getAsString();
            case CompoundTag nested -> read(nested);
            case ListTag list -> {
                List<Object> items = new ArrayList<>(list.size());
                for (Tag each : list) {
                    items.add(each instanceof CompoundTag wrapped && wrapped.contains(ITEM)
                        ? starlark(wrapped.get(ITEM)) : Starlark.NONE);
                }
                yield StarlarkList.immutableCopyOf(items);
            }
            default -> Starlark.NONE;
        };
    }
}
