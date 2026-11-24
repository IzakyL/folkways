package io.github.izakyl.folkways.assistant.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost.RunReply;
import io.github.izakyl.folkways.core.front.api.Ghost;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ToolResponse(UUID requestId, RunReply result) implements CustomPacketPayload {

    public static final Type<ToolResponse> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "tool_response"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Ghost>> GHOSTS =
            Ghost.STREAM_CODEC.apply(ByteBufCodecs.list(Ghost.MAX_PREVIEW_GHOSTS)).cast();

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolResponse> CODEC = StreamCodec.of(
            (buffer, response) -> {
                buffer.writeUUID(response.requestId());
                RunReply result = response.result();
                result.reply().encode(buffer);
                GHOSTS.encode(buffer, result.reply().ghosts());
                buffer.writeBoolean(result.scratch() != null);
                if (result.scratch() != null) {
                    ToolRequest.writeScratch(buffer, result.scratch());
                }
                buffer.writeVarInt(result.captures().size());
                for (JsonObject capture : result.captures()) {
                    buffer.writeUtf(capture.toString(), 4096);
                }
                buffer.writeVarInt(result.historyReads());
            }, buffer -> new ToolResponse(buffer.readUUID(), new RunReply(readReply(buffer),
                    buffer.readBoolean() ? ToolRequest.readScratch(buffer) : null,
                    readCaptures(buffer), buffer.readVarInt())));

    private static ToolReply readReply(RegistryFriendlyByteBuf buffer) {
        ToolReply reply = ToolReply.decode(buffer);
        return new ToolReply(reply.data(), reply.texts(), GHOSTS.decode(buffer));
    }

    private static List<JsonObject> readCaptures(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > CaptureRequest.MAX_PER_RUN) {
            throw new IllegalArgumentException("Invalid number of captures");
        }
        List<JsonObject> captures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            captures.add(JsonParser.parseString(buffer.readUtf(4096)).getAsJsonObject());
        }
        return List.copyOf(captures);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
