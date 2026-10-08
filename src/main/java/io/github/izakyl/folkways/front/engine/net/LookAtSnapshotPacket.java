package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.notice.Line;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record LookAtSnapshotPacket(List<Line> lines, List<LookLines> residents) implements CustomPacketPayload {
    private static final int MAX_LINES = 12;
    public static final Type<LookAtSnapshotPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "look_at_snapshot")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, LookAtSnapshotPacket> STREAM_CODEC = StreamCodec.composite(
        SyncCodecs.lines(MAX_LINES), LookAtSnapshotPacket::lines,
        SyncCodecs.capped(LookLines.STREAM_CODEC, LookLines.MAX_RESIDENTS), LookAtSnapshotPacket::residents,
        LookAtSnapshotPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
