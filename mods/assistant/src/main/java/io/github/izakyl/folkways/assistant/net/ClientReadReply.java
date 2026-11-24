package io.github.izakyl.folkways.assistant.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.izakyl.folkways.assistant.AssistantMod;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientReadReply(UUID requestId, boolean success, String json) implements CustomPacketPayload {

    public static final int MAX_BYTES = 256 * 1024;

    public static final Type<ClientReadReply> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "client_read_reply"));

    public ClientReadReply {
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Client history reply exceeds 256 KiB");
        }
    }

    public static ClientReadReply success(UUID requestId, JsonElement data) {
        return new ClientReadReply(requestId, true, data.toString());
    }

    public static ClientReadReply failure(UUID requestId, String reason) {
        return new ClientReadReply(requestId, false, new JsonPrimitive(reason).toString());
    }

    public JsonElement data() {
        return JsonParser.parseString(json);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientReadReply> CODEC = StreamCodec.of(
            (buffer, reply) -> {
                buffer.writeUUID(reply.requestId());
                buffer.writeBoolean(reply.success());
                buffer.writeByteArray(reply.json().getBytes(StandardCharsets.UTF_8));
            }, buffer -> new ClientReadReply(buffer.readUUID(), buffer.readBoolean(),
                    new String(buffer.readByteArray(MAX_BYTES), StandardCharsets.UTF_8)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
