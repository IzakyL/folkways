package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.jpi.ai.SchemaValidator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class MechanicsReads {

    private static final List<String> TOPICS = List.of("stations", "vocations", "zones", "recipes", "workshops");
    private static final int MAX_LIMIT = 128;
    private static final int MAX_BYTES = 24 * 1024;

    private MechanicsReads() {
    }

    static ToolSpec spec() {
        JsonObject properties = ActionTools.fields("topic:string");
        properties.getAsJsonObject("topic").add("enum", strings(TOPICS));
        properties.add("query", ActionTools.fields("query:string").get("query"));
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        properties.add("limit", limit);
        return ToolRegistry.spec("mechanics", "Read the static type catalogue or the current colony's published workshops. "
                + "Omit topic for topics and the patterns() reference; query alone selects recipes. "
                + "Filter by item id/name, ingredient tag, recipe or station id. Recipes are one processing step, "
                + "not a recursive plan. Station recipe_query can be passed to mechanics('recipes', query=...). "
                + "Default limit 32, maximum 128; query at most 256 characters. Results are proactively trimmed "
                + "with truncated=true to fit 24 KiB. Requires a logged-in player.",
                properties);
    }

    public static ToolReply read(ServerPlayer player, JsonObject args) {
        if (!player.getServer().getPlayerList().getPlayers().contains(player) || player.isRemoved()) {
            return ToolReply.error("not_logged_in");
        }
        if (!SchemaValidator.validate(spec().schema(), args).isEmpty()) {
            return ToolReply.error("invalid_arguments");
        }
        String topic = args.has("topic") ? args.get("topic").getAsString() : args.has("query") ? "recipes" : "";
        String query = args.has("query") ? args.get("query").getAsString() : "";
        int limit;
        try {
            limit = args.has("limit") ? args.get("limit").getAsBigDecimal().intValueExact() : 32;
        } catch (ArithmeticException invalid) {
            return ToolReply.error("invalid_arguments");
        }
        if (limit < 1 || limit > MAX_LIMIT || query.length() > 256) {
            return ToolReply.error("invalid_arguments");
        }
        JsonObject data = new JsonObject();
        data.addProperty("patterns", "patterns()");
        data.addProperty("truncated", false);
        if (topic.isEmpty()) {
            data.add("topics", strings(TOPICS));
            return new ToolReply(data, List.of());
        }
        List<Function<ToolText, JsonObject>> rows = rows(player, topic, query.strip().toLowerCase(Locale.ROOT));
        return trimmed(player, topic, rows, limit, MAX_BYTES);
    }

    private static List<Function<ToolText, JsonObject>> rows(ServerPlayer player, String topic, String query) {
        List<Function<ToolText, JsonObject>> rows = new ArrayList<>();
        switch (topic) {
            case "stations" -> {
                var recipes = query.isEmpty() ? List.<Mechanics.Processing>of()
                        : Mechanics.recipes(player.getServer().getRecipeManager(), player.registryAccess());
                for (var station : Mechanics.stations()) {
                    if (matches(station, query) || recipes.stream().anyMatch(recipe ->
                            recipe.station().equals(station.id()) && matches(recipe, query))) {
                        rows.add(text -> station(station, text));
                    }
                }
            }
            case "workshops" -> CurrentColony.of(player).ifPresent(colony -> {
                for (var workshop : Mechanics.workshops(colony, player.getServer())) {
                    if (matches(query, workshop.id().toString(), workshop.kind().toString(),
                            workshop.name().getString())
                            || workshop.station().filter(station -> matches(station, query)).isPresent()
                            || workshop.facility().filter(facility -> facility.recipeType()
                                .filter(type -> matches(query, type.toString())).isPresent()
                                || facility.explicitRecipes().stream().anyMatch(recipe ->
                                    recipe.inputs().stream().anyMatch(input -> matches(input.spec(), query))
                                    || recipe.outputs().stream().anyMatch(output -> matches(output.spec(), query))))
                                .isPresent()) {
                        rows.add(text -> workshop(workshop, text, player.registryAccess()));
                    }
                }
            });
            case "vocations" -> {
                for (var vocation : Mechanics.vocations()) {
                    if (matches(query, vocation.id().toString(), vocation.name().getString(),
                            vocation.purpose().getString())) {
                        rows.add(text -> {
                            JsonObject row = new JsonObject();
                            row.addProperty("id", vocation.id().toString());
                            row.add("name", text.text(vocation.name()));
                            row.add("purpose", text.text(vocation.purpose()));
                            row.add("stations", strings(Mechanics.stations().stream()
                                    .filter(station -> station.vocations().contains(vocation.id()))
                                    .map(station -> station.id().toString()).toList()));
                            return row;
                        });
                    }
                }
            }
            case "zones" -> {
                for (var kind : Mechanics.kinds()) {
                    if (matches(query, kind.id().toString(), kind.name().getString(), kind.purpose().getString())) {
                        rows.add(text -> {
                            JsonObject row = new JsonObject();
                            row.addProperty("id", kind.id().toString());
                            row.add("name", text.text(kind.name()));
                            row.add("purpose", text.text(kind.purpose()));
                            row.addProperty("shape", kind.shape());
                            row.addProperty("limit", kind.limit());
                            return row;
                        });
                    }
                }
            }
            case "recipes" -> {
                for (var recipe : Mechanics.recipes(player.getServer().getRecipeManager(), player.registryAccess())) {
                    if (matches(recipe, query)) {
                        rows.add(text -> recipe(recipe, text, player.registryAccess()));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown mechanics topic");
        }
        return rows;
    }

    public static ToolReply trimmed(ServerPlayer player, String topic, List<Function<ToolText, JsonObject>> rows,
                             int limit, int bytes) {
        JsonObject data = new JsonObject();
        data.addProperty("topic", topic);
        data.addProperty("patterns", "patterns()");
        data.addProperty("truncated", false);
        JsonArray entries = new JsonArray();
        data.add("entries", entries);
        List<Component> texts = new ArrayList<>();
        for (var row : rows) {
            if (entries.size() == limit) {
                data.addProperty("truncated", true);
                break;
            }
            ToolText text = new ToolText();
            // Start each row with the preceding references so indices remain stable after trimming.
            texts.forEach(text::text);
            entries.add(row.apply(text));
            ToolReply candidate = text.reply(data);
            long size = data.toString().getBytes(StandardCharsets.UTF_8).length;
            for (Component component : candidate.texts()) {
                size += Component.Serializer.toJson(component, player.registryAccess())
                        .getBytes(StandardCharsets.UTF_8).length;
            }
            if (size > bytes || candidate.capped(player.registryAccess()).data().has("error")) {
                entries.remove(entries.size() - 1);
                data.addProperty("truncated", true);
                break;
            }
            texts = new ArrayList<>(candidate.texts());
        }
        return new ToolReply(data, texts);
    }

    private static JsonObject station(Mechanics.Station station, ToolText text) {
        JsonObject row = new JsonObject();
        row.addProperty("id", station.id().toString());
        row.add("name", text.text(station.name()));
        row.addProperty("delegation", station.delegation().toString());
        row.add("placement", text.text(Component.translatable("folkways.mechanics.register_block")));
        row.add("blocks", strings(station.blocks().stream().map(Object::toString).toList()));
        row.add("vocations", strings(station.vocations().stream().map(Object::toString).toList()));
        row.add("recipe_types", strings(station.recipeTypes().stream().map(Object::toString).toList()));
        row.addProperty("recipe_query", station.id().toString());
        row.add("conditions", conditions(station.conditions(), text));
        return row;
    }

    private static JsonObject workshop(Mechanics.WorkshopEntry workshop, ToolText text,
                                       HolderLookup.Provider registries) {
        JsonObject row = new JsonObject();
        row.addProperty("id", workshop.id().toString());
        row.addProperty("kind", workshop.kind().toString());
        row.add("name", text.text(workshop.name()));
        row.addProperty("site", workshop.site().toString());
        workshop.station().ifPresent(station -> row.add("station", station(station, text)));
        workshop.facility().ifPresent(facility -> {
            row.addProperty("facility", facility.id().toString());
            row.add("recipe_types", strings(facility.recipeType().stream().map(Object::toString).toList()));
            if (facility.recipeType().isEmpty()) {
                JsonArray recipes = new JsonArray();
                for (var recipe : facility.explicitRecipes()) {
                    JsonObject batch = new JsonObject();
                    JsonArray inputs = new JsonArray();
                    recipe.inputs().forEach(input -> inputs.add(amount(input.spec(), input.count(), text, registries)));
                    batch.add("inputs", inputs);
                    JsonArray outputs = new JsonArray();
                    recipe.outputs().forEach(output -> outputs.add(amount(output.spec(), output.count(), text, registries)));
                    batch.add("outputs", outputs);
                    recipes.add(batch);
                }
                row.add("recipes", recipes);
            }
        });
        return row;
    }

    private static JsonObject recipe(Mechanics.Processing recipe, ToolText text, HolderLookup.Provider registries) {
        JsonObject row = new JsonObject();
        row.addProperty("id", recipe.id().toString());
        var station = Mechanics.stations().stream().filter(value -> value.id().equals(recipe.station()))
                .findFirst().orElseThrow();
        row.add("station", station(station, text));
        JsonArray inputs = new JsonArray();
        recipe.inputs().forEach(input -> inputs.add(amount(input.spec(), input.count(), text, registries)));
        row.add("inputs", inputs);
        JsonArray outputs = new JsonArray();
        recipe.outputs().forEach(output -> outputs.add(amount(output.spec(), output.count(), text, registries)));
        row.add("outputs", outputs);
        row.add("conditions", conditions(recipe.conditions(), text));
        return row;
    }

    private static JsonObject amount(ItemSpec spec, long count, ToolText text, HolderLookup.Provider registries) {
        JsonObject row = new JsonObject();
        row.addProperty("query", ToolText.query(spec, registries));
        row.addProperty("count", count);
        JsonArray alternatives = new JsonArray();
        Goods.members(spec).stream().map(BuiltInRegistries.ITEM::getKey).sorted().forEach(id -> {
            JsonObject item = new JsonObject();
            item.addProperty("item", id.toString());
            item.add("name", text.text(Component.translatable(BuiltInRegistries.ITEM.get(id).getDescriptionId())));
            alternatives.add(item);
        });
        row.add("alternatives", alternatives);
        return row;
    }

    private static JsonArray conditions(List<Component> conditions, ToolText text) {
        JsonArray result = new JsonArray();
        conditions.forEach(condition -> result.add(text.text(condition)));
        return result;
    }

    private static boolean matches(Mechanics.Station station, String query) {
        return matches(query, station.id().toString(), station.name().getString(), station.delegation().toString())
                || station.blocks().stream().anyMatch(block -> matches(query, block.toString()));
    }

    private static boolean matches(Mechanics.Processing recipe, String query) {
        return matches(query, recipe.id().toString(), recipe.station().toString())
                || recipe.inputs().stream().anyMatch(input -> matches(input.spec(), query))
                || recipe.outputs().stream().anyMatch(output -> matches(output.spec(), query));
    }

    private static boolean matches(ItemSpec spec, String query) {
        if (!spec.anyOf().isEmpty()) {
            return spec.anyOf().stream().anyMatch(part -> matches(part, query));
        }
        String kind = spec.item().map(Object::toString)
            .orElseGet(() -> "#" + spec.tag().orElseThrow().location());
        return matches(query, kind) || Goods.members(spec).stream().anyMatch(item ->
            matches(query, BuiltInRegistries.ITEM.getKey(item).toString(), item.getDescription().getString()));
    }

    private static boolean matches(String query, String... values) {
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }
}
