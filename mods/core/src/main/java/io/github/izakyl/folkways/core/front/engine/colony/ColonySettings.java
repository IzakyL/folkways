package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
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
            switch (entry.kind()) {
                case ITEM -> allowed.add(ItemSpec.of(entry.id()));
                case TAG -> allowed.add(ItemSpec.of(TagKey.create(Registries.ITEM, entry.id())));
                // A query stands for what it finds now: tags reloaded, it is asked again.
                case QUERY -> ItemQuery.parse(entry.text()).ifPresent(query -> query.items()
                    .forEach(item -> allowed.add(ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)))));
            }
        }
        return allowed.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(allowed));
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
        return conformedTo(schema, Set.of());
    }

    public ColonySettings conformedTo(Schema schema, Set<String> inherited) {
        Map<String, Value> next = new LinkedHashMap<>(byKey);
        for (Schema.Setting declared : schema.settings()) {
            Value held = byKey.get(declared.key());
            if (held == null && inherited.contains(declared.key())) {
                continue;
            }
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
            case Value.Items items -> saved.children("items", items.value(), entry -> switch (entry.kind()) {
                case ITEM -> Writer.of().id("item", entry.id()).tag();
                case TAG -> Writer.of().id("tag", entry.id()).tag();
                case QUERY -> Writer.of().string("query", entry.text()).tag();
            });
        }
        return saved.tag();
    }

    public static ColonySettings load(Reader reader) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (String key : reader.keys()) {
            values.put(key, loadValue(reader.child(key).orElseThrow()));
        }
        return new ColonySettings(values);
    }

    private static Value loadValue(Reader reader) {
        // Schema changes can invalidate a well-formed choice or count, but cannot produce malformed NBT.
        if (reader.keys().size() != 1) {
            throw new IllegalArgumentException("A persisted setting must have exactly one value");
        }
        return switch (reader.keys().iterator().next()) {
            case "flag" -> new Value.Flag(reader.flag("flag").orElseThrow());
            case "count" -> new Value.Count(reader.integer("count").orElseThrow());
            case "choice" -> new Value.Choice(reader.id("choice").orElseThrow());
            case "items" -> new Value.Items(reader.requiredChildren("items").stream()
                .map(ColonySettings::readEntry).toList());
            default -> throw new IllegalArgumentException("Unknown persisted setting value");
        };
    }

    private static ItemFilter readEntry(Reader entry) {
        if (entry.keys().size() != 1) {
            throw new IllegalArgumentException("A persisted item filter must have exactly one value");
        }
        return switch (entry.keys().iterator().next()) {
            case "tag" -> ItemFilter.tag(entry.id("tag").orElseThrow());
            case "query" -> ItemFilter.query(entry.string("query").orElseThrow()).orElseThrow();
            case "item" -> ItemFilter.item(entry.id("item").orElseThrow());
            default -> throw new IllegalArgumentException("Unknown persisted item filter");
        };
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
