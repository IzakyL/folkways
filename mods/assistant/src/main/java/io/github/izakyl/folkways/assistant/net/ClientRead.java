package io.github.izakyl.folkways.assistant.net;

import io.github.izakyl.folkways.assistant.AssistantMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientRead(UUID runId, UUID requestId, String path) implements CustomPacketPayload {

    public static final Type<ClientRead> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "client_read"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientRead> CODEC = StreamCodec.of(
            (buffer, request) -> {
                buffer.writeUUID(request.runId());
                buffer.writeUUID(request.requestId());
                buffer.writeUtf(request.path(), 128);
            }, buffer -> new ClientRead(buffer.readUUID(), buffer.readUUID(), buffer.readUtf(128)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
