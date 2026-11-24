package io.github.izakyl.folkways.core.facility.api.control;

import io.github.izakyl.folkways.core.item.api.ItemFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * A value written to a signal or read from the world. Values carry no meaning of their own: whether a number is an
 * angle in degrees or a speed is the routine's business.
 */
public sealed interface Value {

    Pulse PULSE = new Pulse();

    /** A momentary press: it has no state to read back. */
    record Pulse() implements Value {
    }

    record Text(String value) implements Value {

        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    record Bool(boolean on) implements Value {
    }

    record Real(double value) implements Value {

        public Real {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("a value must be finite, not " + value);
            }
        }
    }

    /** A fixed-length group of numbers written at once, such as a gun mount's yaw and pitch. */
    static Optional<BlockPos> blockPosition(Value value) {
        if (!(value instanceof Vector(List<Double> axes)) || SignalType.BLOCK_POSITION.check(value).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(
            axes.get(0).intValue(), axes.get(1).intValue(), axes.get(2).intValue()));
    }

    record Vector(List<Double> values) implements Value {

        public Vector {
            values = List.copyOf(values);
            if (values.stream().anyMatch(each -> !Double.isFinite(each))) {
                throw new IllegalArgumentException("a vector holds finite numbers, not " + values);
            }
        }
    }

    record Sequence(List<Value> values) implements Value {
        public Sequence {
            values = List.copyOf(values);
        }
    }

    /** A list of item rules: items, tags and searches, as an item list setting holds them. */
    record Items(List<ItemFilter> filters) implements Value {
        public Items {
            filters = List.copyOf(filters);
        }
    }

    /** Anything else an adapter understands, kept as data. */
    record Data(CompoundTag tag) implements Value {

        public Data {
            tag = tag.copy();
        }

        @Override
        public CompoundTag tag() {
            return tag.copy();
        }
    }

    static Value of(boolean on) {
        return new Bool(on);
    }

    static Value of(double value) {
        return new Real(value);
    }

    static Value of(double... values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double each : values) {
            boxed.add(each);
        }
        return new Vector(boxed);
    }

    default CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        switch (this) {
            case Text text -> {
                tag.putString("type", "text");
                tag.putString("value", text.value());
            }
            case Pulse ignored -> tag.putString("type", "pulse");
            case Bool(boolean on) -> {
                tag.putString("type", "bool");
                tag.putBoolean("value", on);
            }
            case Real(double value) -> {
                tag.putString("type", "real");
                tag.putDouble("value", value);
            }
            case Vector(List<Double> values) -> {
                tag.putString("type", "vector");
                ListTag list = new ListTag();
                values.forEach(each -> list.add(DoubleTag.valueOf(each)));
                tag.put("value", list);
            }
            case Sequence(List<Value> values) -> {
                tag.putString("type", "sequence");
                ListTag list = new ListTag();
                values.forEach(each -> list.add(each.save()));
                tag.put("value", list);
            }
            case Items(List<ItemFilter> filters) -> {
                tag.putString("type", "items");
                ListTag list = new ListTag();
                filters.forEach(each -> list.add(StringTag.valueOf(each.write())));
                tag.put("value", list);
            }
            case Data(CompoundTag data) -> {
                tag.putString("type", "data");
                tag.put("value", data);
            }
        }
        return tag;
    }

    static Value load(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        return switch (tag.getString("type")) {
            case "text" -> new Text(tag.getString("value"));
            case "pulse" -> PULSE;
            case "bool" -> new Bool(tag.getBoolean("value"));
            case "real" -> new Real(tag.getDouble("value"));
            case "vector" -> {
                ListTag list = tag.getList("value", Tag.TAG_DOUBLE);
                List<Double> values = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    values.add(list.getDouble(i));
                }
                yield new Vector(values);
            }
            case "sequence" -> new Sequence(tag.getList("value", Tag.TAG_COMPOUND).stream()
                .map(each -> load((CompoundTag) each)).toList());
            case "items" -> new Items(tag.getList("value", Tag.TAG_STRING).stream()
                .flatMap(each -> ItemFilter.read(each.getAsString()).stream()).toList());
            case "data" -> new Data(tag.getCompound("value"));
            default -> throw new IllegalArgumentException("unknown value type " + tag.getString("type"));
        };
    }

    /** Values by name, saved as one tag. */
    static CompoundTag saveAll(Map<String, Value> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach((name, value) -> tag.put(name, value.save()));
        return tag;
    }

    static Map<String, Value> loadAll(CompoundTag tag) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (String name : tag.getAllKeys()) {
            values.put(name, load(tag.getCompound(name)));
        }
        return values;
    }
}
