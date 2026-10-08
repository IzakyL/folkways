package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public final class Chosen implements Settings {

    private final Map<String, Boolean> flags = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, ResourceLocation> choices = new LinkedHashMap<>();
    private final Map<String, ItemSpec> lists = new LinkedHashMap<>();

    private Chosen() {
    }

    public static Chosen of(Schema schema, Settings picked) {
        Chosen chosen = new Chosen();
        for (Schema.Setting setting : schema.settings()) {
            String key = setting.key();
            switch (setting) {
                case Schema.Setting.Flag flag -> chosen.flags.put(key, picked.flag(key));
                case Schema.Setting.Count count ->
                    chosen.counts.put(key, Math.max(count.min(), Math.min(count.max(), picked.count(key))));
                case Schema.Setting.Choice choice -> chosen.choices.put(key, within(choice, choiceOf(picked, key)));
                case Schema.Setting.Items items -> picked.items(key).ifPresent(spec -> chosen.lists.put(key, spec));
            }
        }
        return chosen;
    }

    public static Chosen load(Schema schema, CompoundTag saved) {
        Reader reader = Reader.of(saved);
        Chosen read = new Chosen();
        for (Schema.Setting setting : schema.settings()) {
            String key = setting.key();
            switch (setting) {
                case Schema.Setting.Flag flag -> read.flags.put(key, reader.flag(key).orElse(flag.byDefault()));
                case Schema.Setting.Count count -> read.counts.put(key,
                    Math.max(count.min(), Math.min(count.max(), reader.integer(key).orElse(count.byDefault()))));
                case Schema.Setting.Choice choice -> read.choices.put(key, within(choice, reader.id(key).orElse(null)));
                case Schema.Setting.Items items ->
                    reader.child(key).flatMap(ItemSpec::load).ifPresent(spec -> read.lists.put(key, spec));
            }
        }
        return read;
    }

    public CompoundTag save() {
        Writer writer = Writer.of();
        flags.forEach(writer::flag);
        counts.forEach(writer::integer);
        choices.forEach(writer::id);
        lists.forEach((key, spec) -> writer.blob(key, spec.save()));
        return writer.tag();
    }

    private static ResourceLocation choiceOf(Settings picked, String key) {
        try {
            return picked.choice(key);
        } catch (RuntimeException undeclared) {
            return null;
        }
    }

    private static ResourceLocation within(Schema.Setting.Choice choice, ResourceLocation picked) {
        return picked != null && choice.options().contains(picked) ? picked : choice.byDefault();
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
}
