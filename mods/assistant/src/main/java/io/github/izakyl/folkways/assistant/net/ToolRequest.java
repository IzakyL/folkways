package io.github.izakyl.folkways.assistant.net;

import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ToolRequest(UUID requestId, String script, String view, String scratch, boolean imageInput,
        String language)
        implements CustomPacketPayload {

    public static final Type<ToolRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "tool_request"));

    // A question snapshot can carry all 4096 points of a Colony Book path.
    private static final int MAX_QUESTION_SNAPSHOT = 224 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolRequest> CODEC = StreamCodec.of(
            (buffer, request) -> {
                buffer.writeUUID(request.requestId());
                buffer.writeUtf(request.script(), 8192);
                buffer.writeUtf(request.view(), MAX_QUESTION_SNAPSHOT);
                writeScratch(buffer, request.scratch());
                buffer.writeBoolean(request.imageInput());
                buffer.writeUtf(request.language(), 32);
            }, buffer -> new ToolRequest(buffer.readUUID(), buffer.readUtf(8192), buffer.readUtf(MAX_QUESTION_SNAPSHOT),
                    readScratch(buffer), buffer.readBoolean(), buffer.readUtf(32)));

    static void writeScratch(RegistryFriendlyByteBuf buffer, String scratch) {
        buffer.writeByteArray(scratch.getBytes(StandardCharsets.UTF_8));
    }

    static String readScratch(RegistryFriendlyByteBuf buffer) {
        return new String(buffer.readByteArray(ScriptValues.MAX_SCRATCH_BYTES), StandardCharsets.UTF_8);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
