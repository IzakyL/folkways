package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestSitePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RequestSitePacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "ui_request_site"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSitePacket> STREAM_CODEC =
        BlockPos.STREAM_CODEC.map(RequestSitePacket::new, RequestSitePacket::pos).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
