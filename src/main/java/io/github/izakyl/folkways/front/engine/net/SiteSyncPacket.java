package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SiteSyncPacket(BlockPos pos, boolean member, boolean offered)
    implements CustomPacketPayload {

    public static final Type<SiteSyncPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "ui_site"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SiteSyncPacket> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SiteSyncPacket::pos,
        ByteBufCodecs.BOOL, SiteSyncPacket::member,
        ByteBufCodecs.BOOL, SiteSyncPacket::offered,
        SiteSyncPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
