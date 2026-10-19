package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ToggleColonyResidentPacket(UUID colonyId, int entityId) implements CustomPacketPayload {
    public static final Type<ToggleColonyResidentPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "toggle_colony_resident")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleColonyResidentPacket> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, ToggleColonyResidentPacket::colonyId,
        ByteBufCodecs.VAR_INT, ToggleColonyResidentPacket::entityId,
        ToggleColonyResidentPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
