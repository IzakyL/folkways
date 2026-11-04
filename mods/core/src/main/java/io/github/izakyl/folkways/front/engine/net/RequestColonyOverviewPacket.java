package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestColonyOverviewPacket(UUID colonyId, boolean recipesNeeded, List<UUID> unavailable)
        implements CustomPacketPayload {
    public RequestColonyOverviewPacket(UUID colonyId) {
        this(colonyId, false, List.of());
    }

    public static final Type<RequestColonyOverviewPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "request_colony_overview")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestColonyOverviewPacket> STREAM_CODEC =
        StreamCodec.composite(UUIDUtil.STREAM_CODEC, RequestColonyOverviewPacket::colonyId,
            ByteBufCodecs.BOOL, RequestColonyOverviewPacket::recipesNeeded,
            SyncCodecs.capped(UUIDUtil.STREAM_CODEC, 512), RequestColonyOverviewPacket::unavailable,
            RequestColonyOverviewPacket::new).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
