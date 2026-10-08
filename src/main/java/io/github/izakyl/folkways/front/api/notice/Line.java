package io.github.izakyl.folkways.front.api.notice;

import io.github.izakyl.folkways.core.api.work.Doing;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public sealed interface Line {

    record Said(Notice notice) implements Line {
    }

    record Literal(String text) implements Line {
        public Literal {
            text = Notice.trim(text, Notice.MAX_ARG);
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

    record Goods(List<Stack> stacks) implements Line {
        public static final int MOST_STACKS = 8;

        public Goods {
            stacks = List.copyOf(stacks.size() > MOST_STACKS ? stacks.subList(0, MOST_STACKS) : stacks);
        }
    }

    record Stack(ResourceLocation item, long count) {
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

    static void encodeAll(FriendlyByteBuf buffer, List<Line> lines, int max) {
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
                    buffer.writeVarInt(goods.stacks().size());
                    for (Stack stack : goods.stacks()) {
                        buffer.writeResourceLocation(stack.item());
                        buffer.writeVarLong(stack.count());
                    }
                }
                case Told told -> {
                    buffer.writeVarInt(7);
                    told.sentence().encode(buffer);
                }
            }
        }
    }

    static List<Line> decodeAll(FriendlyByteBuf buffer, int max) {
        int count = buffer.readVarInt();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Line line = switch (buffer.readVarInt()) {
                case 1 -> literal(buffer.readUtf(Notice.MAX_ARG));
                case 2 -> new Blocked(Notice.decode(buffer), buffer.readOptional(Sentence::decode));
                case 3 -> busy(Doing.decode(buffer));
                case 4 -> gauge(Meter.decode(buffer), buffer.readVarInt(), buffer.readVarInt());
                case 5 -> bar(buffer.readVarInt(), buffer.readVarInt());
                case 6 -> goods(decodeStacks(buffer));
                case 7 -> told(Sentence.decode(buffer));
                default -> said(Notice.decode(buffer));
            };
            if (i < max) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private static List<Stack> decodeStacks(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > Goods.MOST_STACKS) {
            throw new DecoderException("too many goods in a line: " + count);
        }
        List<Stack> stacks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stacks.add(new Stack(buffer.readResourceLocation(), buffer.readVarLong()));
        }
        return stacks;
    }
}
