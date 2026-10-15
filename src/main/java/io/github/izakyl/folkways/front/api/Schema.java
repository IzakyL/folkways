package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public record Schema(List<Setting> settings) {

    public Schema {
        settings = List.copyOf(settings);
    }

    public static Schema none() {
        return new Schema(List.of());
    }

    public Optional<Setting> find(String key) {
        for (Setting setting : settings) {
            if (setting.key().equals(key)) {
                return Optional.of(setting);
            }
        }
        return Optional.empty();
    }

    public sealed interface Setting {

        String key();

        String nameKey();

        ResourceLocation icon();

        default Component name() {
            return nameKey().isEmpty() ? Component.literal(key()) : Component.translatable(nameKey());
        }

        record Flag(String key, String nameKey, ResourceLocation icon, boolean byDefault)
            implements Setting {
        }

        record Count(String key, String nameKey, ResourceLocation icon, int min, int max, int byDefault)
            implements Setting {
        }

        record Choice(String key, String nameKey, ResourceLocation icon, List<ResourceLocation> options,
                      ResourceLocation byDefault) implements Setting {

            public Choice {
                options = List.copyOf(options);
            }
        }

        // A single one names exactly one item, where the others hold rules: items, tags and searches. A list of
        // blocks offers only what places one.
        record Items(String key, String nameKey, ResourceLocation icon, List<ItemFilter> byDefault, boolean single,
                     boolean blocks) implements Setting {

            public Items {
                byDefault = List.copyOf(byDefault);
            }

            public Items(String key, String nameKey, ResourceLocation icon, List<ItemFilter> byDefault) {
                this(key, nameKey, icon, byDefault, false, false);
            }

            public static Items one(String key, String nameKey, ResourceLocation icon) {
                return new Items(key, nameKey, icon, List.of(), true, false);
            }

            public static Items oneBlock(String key, String nameKey, ResourceLocation icon, ResourceLocation byDefault) {
                return new Items(key, nameKey, icon, List.of(ItemFilter.item(byDefault)), true, true);
            }
        }
    }
}
