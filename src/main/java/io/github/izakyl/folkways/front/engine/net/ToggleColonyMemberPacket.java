package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ToggleColonyMemberPacket(UUID colonyId, BlockPos pos) implements CustomPacketPayload {
    public static final Type<ToggleColonyMemberPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "toggle_colony_member")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleColonyMemberPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, ToggleColonyMemberPacket::colonyId,
        BlockPos.STREAM_CODEC, ToggleColonyMemberPacket::pos,
        ToggleColonyMemberPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
