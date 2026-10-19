package io.github.izakyl.folkways.plugins.build.draft.client;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import net.minecraft.nbt.CompoundTag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class KnobValues implements Settings {

    private final Map<String, Boolean> flags = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, ResourceLocation> choices = new LinkedHashMap<>();
    private final Map<String, ItemSpec> lists = new LinkedHashMap<>();

    private KnobValues() {
    }

    public static KnobValues of(Schema schema) {
        KnobValues values = new KnobValues();
        for (Schema.Setting setting : schema.settings()) {
            switch (setting) {
                case Schema.Setting.Flag flag -> values.flags.put(flag.key(), flag.byDefault());
                case Schema.Setting.Count count -> values.counts.put(count.key(), count.byDefault());
                case Schema.Setting.Choice choice -> values.choices.put(choice.key(), choice.byDefault());
                case Schema.Setting.Items items -> specOf(items.byDefault())
                    .ifPresent(spec -> values.lists.put(items.key(), spec));
            }
        }
        return values;
    }

    static KnobValues load(Schema schema, CompoundTag saved) {
        KnobValues values = of(schema);
        var chosen = Chosen.load(schema, saved);
        for (Schema.Setting setting : schema.settings()) {
            String key = setting.key();
            if (!saved.contains(key)) continue;
            switch (setting) {
                case Schema.Setting.Flag flag -> values.flags.put(key, chosen.flag(key));
                case Schema.Setting.Count count -> values.counts.put(key, chosen.count(key));
                case Schema.Setting.Choice choice -> values.choices.put(key, chosen.choice(key));
                case Schema.Setting.Items items -> {
                    values.lists.remove(key);
                    chosen.items(key).ifPresent(spec -> values.lists.put(key, spec));
                }
            }
        }
        return values;
    }

    public void set(String key, boolean on) {
        flags.put(key, on);
    }

    public void set(Schema.Setting.Count count, int dialled) {
        counts.put(count.key(), Math.max(count.min(), Math.min(count.max(), dialled)));
    }

    public void choose(Schema.Setting.Choice choice, ResourceLocation option) {
        if (choice.options().contains(option)) {
            choices.put(choice.key(), option);
        }
    }

    public void hold(String key, List<ItemFilter> filters) {
        lists.remove(key);
        specOf(filters).ifPresent(spec -> lists.put(key, spec));
    }

    public List<ItemFilter> filtersOf(String key) {
        List<ItemFilter> held = new ArrayList<>();
        items(key).ifPresent(spec -> flatten(spec, held));
        return held;
    }

    private static void flatten(ItemSpec spec, List<ItemFilter> into) {
        spec.item().ifPresent(id -> into.add(ItemFilter.item(id)));
        spec.tag().ifPresent(tag -> into.add(ItemFilter.tag(tag.location())));
        for (ItemSpec alternative : spec.anyOf()) {
            flatten(alternative, into);
        }
    }

    @Override
    public boolean flag(String key) {
        return flags.getOrDefault(key, false);
    }

    @Override
    public int count(String key) {
        return counts.getOrDefault(key, 0);
    }

    @Override
    public ResourceLocation choice(String key) {
        ResourceLocation picked = choices.get(key);
        if (picked == null) {
            throw new IllegalStateException("no choice called " + key);
        }
        return picked;
    }

    @Override
    public Optional<ItemSpec> items(String key) {
        return Optional.ofNullable(lists.get(key));
    }

    private static Optional<ItemSpec> specOf(List<ItemFilter> filters) {
        if (filters.isEmpty()) {
            return Optional.empty();
        }
        List<ItemSpec> alternatives = new ArrayList<>(filters.size());
        for (ItemFilter filter : filters) {
            alternatives.add(filter.tag()
                ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id()))
                : ItemSpec.of(filter.id()));
        }
        return Optional.of(ItemSpec.anyOf(alternatives));
    }
}
