package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.front.api.Placard;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public final class Placards {

    public static final int MAX_LINES = 12;
    static final int MAX_PLACARDS = 256;
    private static final int MAX_POINTS = 512;

    public record Note(UUID id, List<Line> lines) {
        public Note {
            lines = List.copyOf(lines);
        }
    }

    static final StreamCodec<FriendlyByteBuf, Placard.Outline> OUTLINE = StreamCodec.of(Placards::writeOutline,
        Placards::readOutline);

    static final StreamCodec<FriendlyByteBuf, Placard> PLACARD = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, Placard::id,
        OUTLINE, Placard::outline,
        SyncCodecs.lines(MAX_LINES), Placard::lines,
        Placard::new);

    static final StreamCodec<FriendlyByteBuf, Note> NOTE = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, Note::id,
        SyncCodecs.lines(MAX_LINES), Note::lines,
        Note::new);

    private Placards() {
    }

    private static void writeOutline(FriendlyByteBuf buffer, Placard.Outline outline) {
        switch (outline) {
            case Placard.Outline.Box box -> {
                buffer.writeVarInt(0);
                buffer.writeBlockPos(box.min());
                buffer.writeBlockPos(box.max());
            }
            case Placard.Outline.Path path -> {
                List<BlockPos> points = path.points().size() > MAX_POINTS
                    ? path.points().subList(0, MAX_POINTS)
                    : path.points();
                buffer.writeVarInt(1);
                buffer.writeVarInt(points.size());
                points.forEach(buffer::writeBlockPos);
            }
        }
    }

    private static Placard.Outline readOutline(FriendlyByteBuf buffer) {
        return switch (buffer.readVarInt()) {
            case 0 -> new Placard.Outline.Box(buffer.readBlockPos(), buffer.readBlockPos());
            case 1 -> {
                int count = buffer.readVarInt();
                if (count < 1 || count > MAX_POINTS) {
                    throw new DecoderException("placard path has " + count + " points");
                }
                BlockPos[] points = new BlockPos[count];
                for (int at = 0; at < count; at++) {
                    points[at] = buffer.readBlockPos();
                }
                yield new Placard.Outline.Path(List.of(points));
            }
            default -> throw new DecoderException("unknown placard outline");
        };
    }
}
