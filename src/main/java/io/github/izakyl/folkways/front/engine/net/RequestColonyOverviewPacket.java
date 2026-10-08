package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestColonyOverviewPacket(UUID colonyId) implements CustomPacketPayload {
    public static final Type<RequestColonyOverviewPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "request_colony_overview")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestColonyOverviewPacket> STREAM_CODEC =
        UUIDUtil.STREAM_CODEC.map(RequestColonyOverviewPacket::new, RequestColonyOverviewPacket::colonyId).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
