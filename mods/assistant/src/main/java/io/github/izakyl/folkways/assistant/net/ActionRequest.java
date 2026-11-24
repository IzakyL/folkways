package io.github.izakyl.folkways.assistant.net;

import io.github.izakyl.folkways.assistant.AssistantMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// The revision identifies one review unit. In creative, raise sets down its builds at once.
public record ActionRequest(UUID requestId, String revision, boolean accept, boolean raise)
        implements CustomPacketPayload {

    public static final Type<ActionRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "action_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionRequest> CODEC = StreamCodec.of(
            (buffer, request) -> {
                buffer.writeUUID(request.requestId());
                buffer.writeUtf(request.revision(), 160);
                buffer.writeBoolean(request.accept());
                buffer.writeBoolean(request.raise());
            }, buffer -> new ActionRequest(buffer.readUUID(), buffer.readUtf(160), buffer.readBoolean(),
                    buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
