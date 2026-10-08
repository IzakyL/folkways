package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CreatePathPacket(UUID colonyId, List<BlockPos> points, ResourceLocation delegation, CompoundTag settings)
    implements CustomPacketPayload {

    public static final int MAX_POINTS = 4096;

    public static final Type<CreatePathPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "ui_create_path"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreatePathPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, CreatePathPacket::colonyId,
        SyncCodecs.capped(BlockPos.STREAM_CODEC, MAX_POINTS), CreatePathPacket::points,
        ResourceLocation.STREAM_CODEC, CreatePathPacket::delegation,
        ByteBufCodecs.COMPOUND_TAG, CreatePathPacket::settings,
        CreatePathPacket::new);

    public CreatePathPacket {
        points = List.copyOf(points);
        settings = settings == null ? new CompoundTag() : settings.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
