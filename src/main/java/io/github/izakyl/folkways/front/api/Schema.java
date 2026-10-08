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

        record Items(String key, String nameKey, ResourceLocation icon, List<ItemFilter> byDefault)
            implements Setting {

            public Items {
                byDefault = List.copyOf(byDefault);
            }
        }
    }
}
