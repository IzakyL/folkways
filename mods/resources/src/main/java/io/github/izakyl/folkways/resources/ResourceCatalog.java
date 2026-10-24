package io.github.izakyl.folkways.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.InputSubstitutions;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-resources");
    private static volatile List<InputSubstitutions.Rule> current = List.of();

    private ResourceCatalog() { }

    public static List<InputSubstitutions.Rule> current() { return current; }

    public static void reload(ResourceManager resources) {
        List<InputSubstitutions.Rule> next = new ArrayList<>();
        resources.listResources("resource_categories", path -> path.getPath().endsWith(".json"))
            .entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                try (Reader reader = entry.getValue().openAsReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.has("enabled") && !json.get("enabled").getAsBoolean()) return;
                    ResourceLocation resource = ResourceLocation.parse(json.get("resource").getAsString());
                    ResourceLocation fallback = ResourceLocation.parse(json.get("default_item").getAsString());
                    ResourceLocation members = ResourceLocation.parse(json.get("members").getAsString());
                    if (!BuiltInRegistries.ITEM.containsKey(resource) || !BuiltInRegistries.ITEM.containsKey(fallback)
                            || resource.equals(ResourceLocation.withDefaultNamespace("air"))
                            || fallback.equals(ResourceLocation.withDefaultNamespace("air"))) {
                        throw new IllegalArgumentException("unknown resource or default item");
                    }
                    if (next.stream().anyMatch(rule -> rule.payment().equals(ItemSpec.of(resource)))) {
                        throw new IllegalArgumentException("resource belongs to more than one category");
                    }
                    next.add(new InputSubstitutions.Rule(entry.getKey(),
                        ItemSpec.of(TagKey.create(Registries.ITEM, members)), ItemSpec.of(resource),
                        ItemSpec.of(fallback), json.has("cost") ? json.get("cost").getAsBigDecimal().intValueExact() : 1));
                } catch (Exception invalid) {
                    LOGGER.error("invalid resource category {}", entry.getKey(), invalid);
                }
            });
        current = List.copyOf(next);
        InputSubstitutions.replace(FolkwaysResourcesMod.CONTENT, current);
    }
}
