package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestDebugWaypointsPacket(UUID colony) implements CustomPacketPayload {
    public static final Type<RequestDebugWaypointsPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "request_debug_waypoints"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestDebugWaypointsPacket> STREAM_CODEC =
        StreamCodec.composite(UUIDUtil.STREAM_CODEC, RequestDebugWaypointsPacket::colony,
            RequestDebugWaypointsPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
