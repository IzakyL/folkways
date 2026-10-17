package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SweepBlocksPacket(UUID colonyId, BlockPos min, BlockPos max, List<ResourceLocation> kinds)
    implements CustomPacketPayload {

    private static final int MOST_KINDS = 64;

    public static final Type<SweepBlocksPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "ui_sweep_blocks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SweepBlocksPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, SweepBlocksPacket::colonyId,
        BlockPos.STREAM_CODEC, SweepBlocksPacket::min,
        BlockPos.STREAM_CODEC, SweepBlocksPacket::max,
        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list(MOST_KINDS)), SweepBlocksPacket::kinds,
        SweepBlocksPacket::new);

    public SweepBlocksPacket {
        kinds = List.copyOf(kinds);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
