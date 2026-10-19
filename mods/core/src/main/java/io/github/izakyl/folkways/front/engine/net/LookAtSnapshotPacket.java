package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record LookAtSnapshotPacket(List<LookLines> residents) implements CustomPacketPayload {
    public static final Type<LookAtSnapshotPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "look_at_snapshot")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, LookAtSnapshotPacket> STREAM_CODEC = StreamCodec.composite(
        SyncCodecs.capped(LookLines.STREAM_CODEC, LookLines.MAX_RESIDENTS), LookAtSnapshotPacket::residents,
        LookAtSnapshotPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
