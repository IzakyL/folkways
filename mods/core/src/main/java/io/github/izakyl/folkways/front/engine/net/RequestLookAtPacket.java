package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestLookAtPacket(UUID colonyId) implements CustomPacketPayload {
    public static final Type<RequestLookAtPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "request_look_at")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestLookAtPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, RequestLookAtPacket::colonyId,
        RequestLookAtPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
