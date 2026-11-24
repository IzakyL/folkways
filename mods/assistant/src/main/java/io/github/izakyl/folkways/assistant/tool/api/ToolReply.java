package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

// Text references are resolved only by the receiving client, in its language.
public record ToolReply(JsonObject data, List<Component> texts, List<Ghost> ghosts) {

    public static final int MAX_BYTES = 48 * 1024;

    public ToolReply(JsonObject data, List<Component> texts) {
        this(data, texts, List.of());
    }

    public ToolReply {
        data = data.deepCopy();
        ghosts = List.copyOf(ghosts);
        texts = texts.stream().map(Component::copy).map(Component.class::cast).toList();
    }

    public static ToolReply error(String code) {
        JsonObject data = new JsonObject();
        data.addProperty("error", code);
        return new ToolReply(data, List.of());
    }

    /** An invalid_arguments error that says what was wrong, so the caller can fix the call instead of guessing. */
    public static ToolReply invalid(String detail) {
        ToolReply reply = error("invalid_arguments");
        if (detail != null && !detail.isBlank() && !detail.equals("invalid_arguments")) {
            reply.data().addProperty("message", detail);
        }
        return reply;
    }

    public ToolReply capped(RegistryAccess registries) {
        long characters = data.toString().length();
        for (Component text : texts) {
            characters += Component.Serializer.toJson(text, registries).length();
            if (characters > MAX_BYTES) {
                return truncated();
            }
        }
        if (characters > MAX_BYTES) {
            return truncated();
        }
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            encode(buffer);
            if (buffer.readableBytes() <= MAX_BYTES) {
                return this;
            }
        } finally {
            buffer.release();
        }
        return truncated();
    }

    private ToolReply truncated() {
        JsonObject cut = new JsonObject();
        for (String key : List.of("colony_id", "capture_tick")) {
            if (data.has(key)) {
                cut.add(key, data.get(key));
            }
        }
        cut.addProperty("error", "result_size_cap");
        cut.addProperty("truncated", true);
        cut.addProperty("reason", "result_size_cap");
        cut.addProperty("max_bytes", MAX_BYTES);
        cut.addProperty("hint", "Print or return fewer fields; filter items or residents.");
        return new ToolReply(cut, List.of());
    }

    public Component component(String field) {
        JsonObject reference = data.getAsJsonObject(field);
        return texts.get(reference.get("$text").getAsInt()).copy();
    }

    public JsonObject localized() {
        return resolve(data).getAsJsonObject();
    }

    private JsonElement resolve(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.size() == 1 && object.has("$text")) {
                return new JsonPrimitive(texts.get(object.get("$text").getAsInt()).getString());
            }
            JsonObject copy = new JsonObject();
            object.entrySet().forEach(entry -> copy.add(entry.getKey(), resolve(entry.getValue())));
            return copy;
        }
        if (value.isJsonArray()) {
            var copy = new JsonArray();
            value.getAsJsonArray().forEach(entry -> copy.add(resolve(entry)));
            return copy;
        }
        return value.deepCopy();
    }

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(data.toString(), MAX_BYTES);
        buffer.writeVarInt(texts.size());
        for (Component text : texts) {
            ComponentSerialization.STREAM_CODEC.encode(buffer, text);
        }
    }

    public static ToolReply decode(RegistryFriendlyByteBuf buffer) {
        var data = JsonParser.parseString(buffer.readUtf(MAX_BYTES)).getAsJsonObject();
        int count = buffer.readVarInt();
        if (count < 0 || count > 4096) {
            throw new IllegalArgumentException("Too many tool texts");
        }
        var texts = new ArrayList<Component>();
        for (int index = 0; index < count; index++) {
            texts.add(ComponentSerialization.STREAM_CODEC.decode(buffer));
        }
        return new ToolReply(data, texts);
    }
}
