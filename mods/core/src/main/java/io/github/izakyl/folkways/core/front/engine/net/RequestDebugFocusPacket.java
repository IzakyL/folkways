package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestDebugFocusPacket(UUID colony, DebugTarget target) implements CustomPacketPayload {
    public static final Type<RequestDebugFocusPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "request_debug_focus"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestDebugFocusPacket> STREAM_CODEC =
        StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, RequestDebugFocusPacket::colony,
        DebugTarget.STREAM_CODEC, RequestDebugFocusPacket::target,
        RequestDebugFocusPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
