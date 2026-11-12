package io.github.izakyl.folkways.core.front.api.notice;

import io.github.izakyl.folkways.core.resident.api.Doing;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;

public sealed interface Line {

    record Said(Notice notice) implements Line {
    }

    record Literal(String text) implements Line {
        public Literal {
            text = Notice.trim(text, Notice.MAX_ARG);
        }
    }

    /** A display name whose translations are resolved by the client that reads it, and pictures told after it. */
    record Text(Component component, Sentence after) implements Line {
        public Text(Component component) {
            this(component, Sentence.EMPTY);
        }
    }

    /** What stands in the way, in words, and after them in pictures when {@code told}: "Can't eat: ✗ bread". */
    record Blocked(Notice notice, Optional<Sentence> told) implements Line {
        public Blocked(Notice notice) {
            this(notice, Optional.empty());
        }
    }

    record Told(Sentence sentence) implements Line {
    }

    record Busy(Doing doing) implements Line {
    }

    record Gauge(Meter meter, int value, int max) implements Line {
        public Gauge {
            max = Math.max(1, max);
            value = Math.clamp(value, 0, max);
        }
    }

    record Bar(int value, int max) implements Line {
        public Bar {
            max = Math.max(1, max);
            value = Math.clamp(value, 0, max);
        }

        public float fill() {
            return value / (float) max;
        }
    }

    /** Goods in a row; a nonzero {@code tint} (RGB) says what state they are in, and colours their counts. */
    record Goods(List<Stack> stacks, int tint) implements Line {
        public static final int MOST_STACKS = 8;

        public Goods {
            stacks = List.copyOf(stacks.size() > MOST_STACKS ? stacks.subList(0, MOST_STACKS) : stacks);
            tint &= 0xFFFFFF;
        }

        public Goods(List<Stack> stacks) {
            this(stacks, 0);
        }
    }

    record Stack(ResourceLocation item, long count) {
    }

    /**
     * What a store holds, most plentiful first: at most {@link #MOST_SHOWN} goods, how many more kinds go unshown,
     * and how many of its slots are taken out of how many, when the store keeps slots ({@code total} 0 when not).
     * No goods and no more kinds reads as empty.
     */
    record Contents(List<Stack> stacks, int more, int used, int total) implements Line {
        public static final int MOST_SHOWN = 8;

        public Contents {
            stacks = List.copyOf(stacks.size() > MOST_SHOWN ? stacks.subList(0, MOST_SHOWN) : stacks);
            more = Math.max(0, more);
            total = Math.max(0, total);
            used = Math.clamp(used, 0, total);
        }

        public boolean empty() {
            return stacks.isEmpty() && more == 0;
        }
    }

    static Line of(NoticeKind kind, Notice.Arg... args) {
        return new Said(Notice.of(kind, args));
    }

    static Line said(Notice notice) {
        return new Said(notice);
    }

    static Line literal(String text) {
        return new Literal(text);
    }

    static Line text(Component component) {
        return new Text(component);
    }

    static Line blocked(Notice notice) {
        return new Blocked(notice);
    }

    static Line blocked(Notice notice, Sentence told) {
        return new Blocked(notice, Optional.of(told));
    }

    static Line told(Sentence sentence) {
        return new Told(sentence);
    }

    static Line busy(Doing doing) {
        return new Busy(doing);
    }

    static Line gauge(Meter meter, int value, int max) {
        return new Gauge(meter, value, max);
    }

    static Line bar(int value, int max) {
        return new Bar(value, max);
    }

    static Line goods(List<Stack> stacks) {
        return new Goods(stacks);
    }

    static Line goods(List<Stack> stacks, int tint) {
        return new Goods(stacks, tint);
    }

    static Line contents(List<Stack> stacks, int more, int used, int total) {
        return new Contents(stacks, more, used, total);
    }

    static void encodeAll(RegistryFriendlyByteBuf buffer, List<Line> lines, int max) {
        List<Line> capped = lines.size() > max ? lines.subList(0, max) : lines;
        buffer.writeVarInt(capped.size());
        for (Line line : capped) {
            switch (line) {
                case Said said -> {
                    buffer.writeVarInt(0);
                    said.notice().encode(buffer);
                }
                case Literal literal -> {
                    buffer.writeVarInt(1);
                    buffer.writeUtf(literal.text(), Notice.MAX_ARG);
                }
                case Blocked blocked -> {
                    buffer.writeVarInt(2);
                    blocked.notice().encode(buffer);
                    buffer.writeOptional(blocked.told(), (buf, told) -> told.encode(buf));
                }
                case Busy busy -> {
                    buffer.writeVarInt(3);
                    busy.doing().encode(buffer);
                }
                case Gauge gauge -> {
                    buffer.writeVarInt(4);
                    gauge.meter().encode(buffer);
                    buffer.writeVarInt(gauge.value());
                    buffer.writeVarInt(gauge.max());
                }
                case Bar bar -> {
                    buffer.writeVarInt(5);
                    buffer.writeVarInt(bar.value());
                    buffer.writeVarInt(bar.max());
                }
                case Goods goods -> {
                    buffer.writeVarInt(6);
                    writeStacks(buffer, goods.stacks());
                    buffer.writeInt(goods.tint());
                }
                case Told told -> {
                    buffer.writeVarInt(7);
                    told.sentence().encode(buffer);
                }
                case Text text -> {
                    buffer.writeVarInt(8);
                    ComponentSerialization.STREAM_CODEC.encode(buffer, text.component());
                    text.after().encode(buffer);
                }
                case Contents contents -> {
                    buffer.writeVarInt(9);
                    writeStacks(buffer, contents.stacks());
                    buffer.writeVarInt(contents.more());
                    buffer.writeVarInt(contents.used());
                    buffer.writeVarInt(contents.total());
                }
            }
        }
    }

    static List<Line> decodeAll(RegistryFriendlyByteBuf buffer, int max) {
        int count = buffer.readVarInt();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int type = buffer.readVarInt();
            Line line = switch (type) {
                case 0 -> said(Notice.decode(buffer));
                case 1 -> literal(buffer.readUtf(Notice.MAX_ARG));
                case 2 -> new Blocked(Notice.decode(buffer), buffer.readOptional(Sentence::decode));
                case 3 -> busy(Doing.decode(buffer));
                case 4 -> gauge(Meter.decode(buffer), buffer.readVarInt(), buffer.readVarInt());
                case 5 -> bar(buffer.readVarInt(), buffer.readVarInt());
                case 6 -> goods(decodeStacks(buffer, Goods.MOST_STACKS), buffer.readInt());
                case 7 -> told(Sentence.decode(buffer));
                case 8 -> new Text(ComponentSerialization.STREAM_CODEC.decode(buffer), Sentence.decode(buffer));
                case 9 -> contents(decodeStacks(buffer, Contents.MOST_SHOWN), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt());
                default -> throw new DecoderException("Unknown line type: " + type);
            };
            if (i < max) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private static void writeStacks(RegistryFriendlyByteBuf buffer, List<Stack> stacks) {
        buffer.writeVarInt(stacks.size());
        for (Stack stack : stacks) {
            buffer.writeResourceLocation(stack.item());
            buffer.writeVarLong(stack.count());
        }
    }

    private static List<Stack> decodeStacks(RegistryFriendlyByteBuf buffer, int most) {
        int count = buffer.readVarInt();
        if (count < 0 || count > most) {
            throw new DecoderException("too many goods in a line: " + count);
        }
        List<Stack> stacks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stacks.add(new Stack(buffer.readResourceLocation(), buffer.readVarLong()));
        }
        return stacks;
    }
}
