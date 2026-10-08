package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CreateZonePacket(UUID colonyId, BlockPos min, BlockPos max, ResourceLocation delegation, CompoundTag settings)
    implements CustomPacketPayload {

    public static final Type<CreateZonePacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "ui_create_zone"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateZonePacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, CreateZonePacket::colonyId,
        BlockPos.STREAM_CODEC, CreateZonePacket::min,
        BlockPos.STREAM_CODEC, CreateZonePacket::max,
        ResourceLocation.STREAM_CODEC, CreateZonePacket::delegation,
        ByteBufCodecs.COMPOUND_TAG, CreateZonePacket::settings,
        CreateZonePacket::new);

    public CreateZonePacket {
        settings = settings == null ? new CompoundTag() : settings.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
