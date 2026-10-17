package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.front.engine.colony.ColonyZone;
import io.github.izakyl.folkways.front.engine.colony.Zone;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

public record ZoneSnapshot(
    UUID id,
    ResourceLocation delegation,
    BlockPos min,
    BlockPos max
) {
    static final StreamCodec<ByteBuf, ZoneSnapshot> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, ZoneSnapshot::id,
        ResourceLocation.STREAM_CODEC, ZoneSnapshot::delegation,
        BlockPos.STREAM_CODEC, ZoneSnapshot::min,
        BlockPos.STREAM_CODEC, ZoneSnapshot::max,
        ZoneSnapshot::new);

    public static ZoneSnapshot of(ColonyZone zone) {
        Zone box = Zone.over(zone);
        return new ZoneSnapshot(zone.id(), zone.delegation(), box.min(), box.max());
    }
}
