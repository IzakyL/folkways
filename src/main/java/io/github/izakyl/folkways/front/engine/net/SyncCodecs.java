package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.front.api.notice.Line;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;

final class SyncCodecs {

    private SyncCodecs() {
    }

    static <B extends ByteBuf, T> StreamCodec<B, List<T>> capped(StreamCodec<? super B, T> element, int maxSize) {
        return StreamCodec.of((buffer, values) -> {
            int size = Math.min(values.size(), maxSize);
            VarInt.write(buffer, size);
            for (int i = 0; i < size; i++) {
                element.encode(buffer, values.get(i));
            }
        }, buffer -> {
            int count = VarInt.read(buffer);
            List<T> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                T value = element.decode(buffer);
                if (i < maxSize) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        });
    }

    static <B extends FriendlyByteBuf> StreamCodec<B, List<Line>> lines(int max) {
        return StreamCodec.of((buffer, lines) -> Line.encodeAll(buffer, lines, max),
            buffer -> Line.decodeAll(buffer, max));
    }
}
