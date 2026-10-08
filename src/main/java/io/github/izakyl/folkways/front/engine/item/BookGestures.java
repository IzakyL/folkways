package io.github.izakyl.folkways.front.engine.item;

import com.mojang.serialization.Codec;
import io.github.izakyl.folkways.front.api.Shape;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

public final class BookGestures {

    public static final Shape.Gesture DEFAULT = Shape.Gesture.POINT;

    public static final Codec<Shape.Gesture> CODEC = StringRepresentable.fromEnum(Shape.Gesture::values);
    public static final StreamCodec<ByteBuf, Shape.Gesture> STREAM_CODEC =
        ByteBufCodecs.idMapper(id -> Shape.Gesture.values()[id], Shape.Gesture::ordinal);

    private BookGestures() {
    }

    public static List<Shape.Gesture> offered() {
        return List.of(Shape.Gesture.values());
    }

    public static Shape.Gesture next(Shape.Gesture held) {
        List<Shape.Gesture> offered = offered();
        int at = offered.indexOf(held);
        return at < 0 ? offered.get(0) : offered.get((at + 1) % offered.size());
    }
}
