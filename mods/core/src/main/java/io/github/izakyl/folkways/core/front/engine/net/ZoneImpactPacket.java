package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ZoneImpactPacket(CreateZonePacket request, Component affected) implements CustomPacketPayload {

    public static final Type<ZoneImpactPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "ui_zone_impact"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneImpactPacket> STREAM_CODEC = StreamCodec.composite(
        CreateZonePacket.STREAM_CODEC, ZoneImpactPacket::request,
        ComponentSerialization.STREAM_CODEC, ZoneImpactPacket::affected,
        ZoneImpactPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
