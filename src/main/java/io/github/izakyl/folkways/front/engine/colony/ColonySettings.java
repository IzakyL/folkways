package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

public final class ColonySettings implements Settings {

    public sealed interface Value {

        record Flag(boolean value) implements Value {
        }

        record Count(int value) implements Value {
        }

        record Choice(ResourceLocation value) implements Value {
        }

        record Items(List<ItemFilter> value) implements Value {

            public Items {
                value = List.copyOf(value);
            }
        }
    }

    private final Map<String, Value> byKey;

    private ColonySettings(Map<String, Value> byKey) {
        this.byKey = Collections.unmodifiableMap(new LinkedHashMap<>(byKey));
    }

    public static ColonySettings empty() {
        return new ColonySettings(Map.of());
    }

    public static ColonySettings byDefault(Schema schema) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (Schema.Setting declared : schema.settings()) {
            values.put(declared.key(), defaultOf(declared));
        }
        return new ColonySettings(values);
    }

    @Override
    public boolean flag(String key) {
        return byKey.get(key) instanceof Value.Flag set && set.value();
    }

    @Override
    public int count(String key) {
        return byKey.get(key) instanceof Value.Count set ? set.value() : 0;
    }

    @Override
    public ResourceLocation choice(String key) {
        if (byKey.get(key) instanceof Value.Choice set) {
            return set.value();
        }
        throw new IllegalStateException("no choice set for '" + key + "'");
    }

    @Override
    public Optional<ItemSpec> items(String key) {
        List<ItemFilter> entries = entries(key);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        List<ItemSpec> allowed = new ArrayList<>(entries.size());
        for (ItemFilter entry : entries) {
            allowed.add(entry.tag()
                ? ItemSpec.of(TagKey.create(Registries.ITEM, entry.id()))
                : ItemSpec.of(entry.id()));
        }
        return Optional.of(ItemSpec.anyOf(allowed));
    }

    public List<ItemFilter> entries(String key) {
        return byKey.get(key) instanceof Value.Items set ? set.value() : List.of();
    }

    public Set<String> keys() {
        return byKey.keySet();
    }

    public Optional<Value> valueOf(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public ColonySettings with(String key, Value value) {
        Map<String, Value> next = new LinkedHashMap<>(byKey);
        next.put(key, value);
        return new ColonySettings(next);
    }

    public ColonySettings conformedTo(Schema schema) {
        Map<String, Value> next = new LinkedHashMap<>();
        for (Schema.Setting declared : schema.settings()) {
            Value held = byKey.get(declared.key());
            next.put(declared.key(), fits(declared, held) ? held : defaultOf(declared));
        }
        return new ColonySettings(next);
    }

    public CompoundTag save() {
        Writer writer = Writer.of();
        byKey.forEach((key, value) -> writer.blob(key, saveValue(value)));
        return writer.tag();
    }

    private static CompoundTag saveValue(Value value) {
        Writer saved = Writer.of();
        switch (value) {
            case Value.Flag flag -> saved.flag("flag", flag.value());
            case Value.Count count -> saved.integer("count", count.value());
            case Value.Choice choice -> saved.id("choice", choice.value());
            case Value.Items items -> saved.children("items", items.value(), entry ->
                Writer.of().id(entry.tag() ? "tag" : "item", entry.id()).tag());
        }
        return saved.tag();
    }

    public static ColonySettings load(Reader reader) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (String key : reader.keys()) {
            reader.child(key).flatMap(ColonySettings::loadValue).ifPresent(value -> values.put(key, value));
        }
        return new ColonySettings(values);
    }

    private static Optional<Value> loadValue(Reader reader) {
        Optional<Value> flag = reader.flag("flag").map(Value.Flag::new);
        if (flag.isPresent()) {
            return flag;
        }
        Optional<Value> count = reader.integer("count").map(Value.Count::new);
        if (count.isPresent()) {
            return count;
        }
        Optional<Value> choice = reader.id("choice").map(Value.Choice::new);
        if (choice.isPresent()) {
            return choice;
        }
        List<ItemFilter> entries = Entries.of(reader, "items", ColonySettings::readEntry);
        return entries.isEmpty() && reader.children("items").isEmpty()
            ? Optional.empty()
            : Optional.of(new Value.Items(entries));
    }

    private static Optional<ItemFilter> readEntry(Reader entry) {
        Optional<ItemFilter> tag = entry.id("tag").map(ItemFilter::tag);
        return tag.isPresent() ? tag : entry.id("item").map(ItemFilter::item);
    }

    private static Value defaultOf(Schema.Setting declared) {
        return switch (declared) {
            case Schema.Setting.Flag flag -> new Value.Flag(flag.byDefault());
            case Schema.Setting.Count count -> new Value.Count(count.byDefault());
            case Schema.Setting.Choice choice -> new Value.Choice(choice.byDefault());
            case Schema.Setting.Items items -> new Value.Items(items.byDefault());
        };
    }

    private static boolean fits(Schema.Setting declared, Value held) {
        return switch (declared) {
            case Schema.Setting.Flag ignored -> held instanceof Value.Flag;
            case Schema.Setting.Count count -> held instanceof Value.Count set
                && set.value() >= count.min() && set.value() <= count.max();
            case Schema.Setting.Choice choice -> held instanceof Value.Choice set
                && choice.options().contains(set.value());
            case Schema.Setting.Items ignored -> held instanceof Value.Items;
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ColonySettings settings && byKey.equals(settings.byKey);
    }

    @Override
    public int hashCode() {
        return byKey.hashCode();
    }
}
