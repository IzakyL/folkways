package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.front.api.notice.Line;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record LookLines(int entityId, List<Line> lines) {
    public static final int MAX_LINES = 8;
    public static final int MAX_RESIDENTS = 16;
    static final StreamCodec<FriendlyByteBuf, LookLines> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, LookLines::entityId,
        SyncCodecs.lines(MAX_LINES), LookLines::lines,
        LookLines::new);
}
