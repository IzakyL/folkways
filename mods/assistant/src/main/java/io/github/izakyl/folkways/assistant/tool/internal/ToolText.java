package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.card.api.notice.Doings;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public final class ToolText {

    private final List<Component> texts = new ArrayList<>();

    public JsonObject text(Component component) {
        JsonObject ref = new JsonObject();
        ref.addProperty("$text", texts.size());
        texts.add(component);
        return ref;
    }

    public ToolReply reply(JsonObject data) {
        return new ToolReply(data, texts);
    }

    JsonArray lines(List<Line> lines) {
        JsonArray rows = new JsonArray();
        for (Line line : lines) {
            JsonObject row = new JsonObject();
            switch (line) {
                case Line.Said said -> row.add("text", text(said.notice().component()));
                case Line.Literal literal -> row.addProperty("text", literal.text());
                case Line.Text named -> {
                    row.add("text", text(named.component()));
                    row.add("after", sentence(named.after()));
                }
                case Line.Blocked blocked -> {
                    row.addProperty("kind", "blocked");
                    row.addProperty("code", blocked.notice().key());
                    row.add("text", text(blocked.notice().component()));
                    blocked.told().ifPresent(told -> row.add("detail", sentence(told)));
                }
                case Line.Busy busy -> {
                    row.addProperty("kind", "activity");
                    row.addProperty("id", busy.doing().what().toString());
                    row.add("text", text(Doings.said(busy.doing().what(), busy.doing().about()).component()));
                    row.add("detail", sentence(Sentence.of(busy.doing())));
                }
                case Line.Gauge gauge -> {
                    row.addProperty("meter", gauge.meter().icon().toString());
                    row.addProperty("value", gauge.value());
                    row.addProperty("max", gauge.max());
                }
                case Line.Bar bar -> {
                    row.addProperty("value", bar.value());
                    row.addProperty("max", bar.max());
                }
                case Line.Goods goods -> {
                    JsonArray stacks = new JsonArray();
                    goods.stacks().forEach(stack -> stacks.add(ware(stack.item().toString(), stack.count())));
                    row.add("goods", stacks);
                }
                case Line.Contents contents -> {
                    JsonArray stacks = new JsonArray();
                    contents.stacks().forEach(stack -> stacks.add(ware(stack.item().toString(), stack.count())));
                    row.add("goods", stacks);
                    row.addProperty("more", contents.more());
                    row.addProperty("used", contents.used());
                    row.addProperty("total", contents.total());
                }
                case Line.Told told -> row.add("detail", sentence(told.sentence()));
            }
            rows.add(row);
        }
        return rows;
    }

    JsonArray sentence(Sentence sentence) {
        JsonArray tokens = new JsonArray();
        for (Sentence.Token token : sentence.tokens()) {
            JsonObject part = new JsonObject();
            switch (token) {
                case Sentence.Token.Word word -> part.add("text", text(word.notice().component()));
                case Sentence.Token.Glyph glyph -> {
                    part.addProperty("glyph", glyph.sprite().toString());
                    if (glyph.otherwise().isPresent()) {
                        part.add("text", text(glyph.otherwise().get().component()));
                    }
                }
                case Sentence.Token.Ware ware -> part = ware(ware.item().toString(), ware.count());
                case Sentence.Token.Plus ignored -> part.addProperty("operator", "+");
                case Sentence.Token.Yields ignored -> part.addProperty("operator", "yields");
            }
            tokens.add(part);
        }
        return tokens;
    }

    JsonArray schema(Schema schema) {
        JsonArray fields = new JsonArray();
        for (Schema.Setting setting : schema.settings()) {
            JsonObject field = new JsonObject();
            field.addProperty("key", setting.key());
            field.add("name", text(Component.translatable(setting.nameKey())));
            switch (setting) {
                case Schema.Setting.Flag flag -> {
                    field.addProperty("type", "boolean");
                    field.addProperty("default", flag.byDefault());
                }
                case Schema.Setting.Count count -> {
                    field.addProperty("type", "integer");
                    field.addProperty("min", count.min());
                    field.addProperty("max", count.max());
                    field.addProperty("default", count.byDefault());
                }
                case Schema.Setting.Choice choice -> {
                    field.addProperty("type", "choice");
                    JsonArray choices = new JsonArray();
                    choice.options().forEach(id -> choices.add(id.toString()));
                    field.add("options", choices);
                }
                case Schema.Setting.Items items -> {
                    field.addProperty("type", "item_queries");
                    field.addProperty("single", items.single());
                    field.addProperty("blocks_only", items.blocks());
                }
            }
            fields.add(field);
        }
        return fields;
    }

    JsonObject ware(String id, long count) {
        JsonObject result = new JsonObject();
        result.addProperty("item", id);
        result.addProperty("count", count);
        result.add("name", text(Component.translatable(
                BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).getDescriptionId())));
        return result;
    }

    static String query(ItemSpec spec, HolderLookup.Provider registries) {
        if (!spec.anyOf().isEmpty()) {
            return spec.anyOf().stream().map(part -> query(part, registries)).sorted()
                .collect(Collectors.joining(" | "));
        }
        String kind = spec.item().map(Object::toString)
            .orElseGet(() -> "#" + spec.tag().orElseThrow().location());
        return kind + spec.components()
            .map(components -> spec.save(registries).getCompound("components").toString()).orElse("");
    }

    static JsonElement value(ColonySettings.Value value) {
        return switch (value) {
            case ColonySettings.Value.Flag flag -> new JsonPrimitive(flag.value());
            case ColonySettings.Value.Count count -> new JsonPrimitive(count.value());
            case ColonySettings.Value.Choice choice -> new JsonPrimitive(choice.value().toString());
            case ColonySettings.Value.Items items -> {
                JsonArray entries = new JsonArray();
                items.value().forEach(filter -> entries.add(filter.describe()));
                yield entries;
            }
        };
    }

    static Component valueWords(ColonySettings.Value value) {
        return switch (value) {
            case ColonySettings.Value.Flag flag -> Component.translatable(
                    flag.value() ? "folkways.action.on" : "folkways.action.off");
            case ColonySettings.Value.Count count -> Component.literal(Integer.toString(count.value()));
            case ColonySettings.Value.Choice choice -> Component.literal(choice.value().toString());
            case ColonySettings.Value.Items items -> {
                var words = Component.empty();
                for (var filter : items.value()) {
                    if (!words.getSiblings().isEmpty()) {
                        words.append(", ");
                    }
                    words.append(filter.kind() == ItemFilter.Kind.ITEM
                            ? Component.translatable(BuiltInRegistries.ITEM.get(filter.id()).getDescriptionId())
                            : Component.literal(filter.describe()));
                }
                yield words;
            }
        };
    }

    static Component words(Sentence sentence) {
        var words = Component.empty();
        for (Sentence.Token token : sentence.tokens()) {
            words.append(" ");
            switch (token) {
                case Sentence.Token.Word word -> words.append(word.notice().component());
                case Sentence.Token.Glyph glyph -> words.append(glyph.otherwise().map(notice -> notice.component())
                        .orElseGet(() -> Component.literal(glyph.sprite().toString())));
                case Sentence.Token.Ware ware -> words.append(Component.translatable(
                        BuiltInRegistries.ITEM.get(ware.item()).getDescriptionId())).append(" × " + ware.count());
                case Sentence.Token.Plus ignored -> words.append("+");
                case Sentence.Token.Yields ignored -> words.append("→");
            }
        }
        return words;
    }

    static JsonObject position(WorldPos at, MinecraftServer server) {
        JsonObject result = new JsonObject();
        switch (at.realm()) {
            case Realm.Dimension dimension -> result.addProperty("dimension", dimension.id().location().toString());
            case Realm.Frame frame -> result.addProperty("frame", frame.structure().toString());
        }
        at.level(server).ifPresent(level -> result.addProperty("dimension", level.dimension().location().toString()));
        result.addProperty("x", at.cell().getX());
        result.addProperty("y", at.cell().getY());
        result.addProperty("z", at.cell().getZ());
        return result;
    }
}
