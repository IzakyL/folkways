package io.github.izakyl.folkways.core.card.api;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/** Cards, rows and values as they cross the network. */
public final class CardCodec {
    private static final int TEXT = 256;
    private static final int MOST_ELEMENTS = 256;
    private static final int MOST_ROWS = 256;
    private static final int MOST_LINES = 32;
    private static final int MAX_VALUE_TEXT = 32767;

    private CardCodec() {
    }

    public static void write(RegistryFriendlyByteBuf b, Card card) {
        elements(b, card.elements());
        b.writeVarInt(card.overlays().size());
        for (Card.Overlay overlay : card.overlays()) {
            switch (overlay) {
                case Card.Overlay.Range range -> {
                    b.writeByte(0);
                    b.writeUtf(range.reading(), TEXT);
                }
                case Card.Overlay.Path path -> {
                    b.writeByte(1);
                    b.writeUtf(path.routine(), TEXT);
                }
            }
        }
    }

    public static Card read(RegistryFriendlyByteBuf b) {
        var elements = elements(b);
        List<Card.Overlay> overlays = new ArrayList<>();
        for (int n = count(b, 64); n > 0; n--) {
            overlays.add(switch (b.readByte()) {
                case 0 -> new Card.Overlay.Range(b.readUtf(TEXT));
                case 1 -> new Card.Overlay.Path(b.readUtf(TEXT));
                default -> throw new IllegalArgumentException("invalid card overlay");
            });
        }
        return new Card(elements, overlays);
    }

    private static void elements(RegistryFriendlyByteBuf b, List<Card.Element> elements) {
        b.writeVarInt(elements.size());
        for (Card.Element element : elements) {
            switch (element) {
                case Card.Element.Button button -> {
                    b.writeByte(0);
                    b.writeUtf(button.routine(), TEXT);
                    b.writeOptional(button.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    values(b, button.enabledWhen());
                    label(b, button.label());
                }
                case Card.Element.Toggle toggle -> {
                    b.writeByte(1);
                    b.writeUtf(toggle.trigger(), TEXT);
                    b.writeOptional(toggle.icon(), (out, icon) -> out.writeResourceLocation(icon));
                }
                case Card.Element.Readout readout -> {
                    b.writeByte(2);
                    b.writeUtf(readout.reading(), TEXT);
                    b.writeEnum(readout.as());
                    label(b, readout.label());
                }
                case Card.Element.Target target -> {
                    b.writeByte(3);
                    b.writeUtf(target.routine(), TEXT);
                    b.writeEnum(target.pick());
                    b.writeOptional(target.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    label(b, target.label());
                    b.writeBoolean(target.defaultCommand());
                    values(b, target.enabledWhen());
                    values(b, target.arguments());
                }
                case Card.Element.Choice choice -> {
                    b.writeByte(4);
                    b.writeUtf(choice.param(), TEXT);
                    strings(b, choice.options());
                    strings(b, choice.labels());
                    b.writeVarInt(choice.icons().size());
                    choice.icons().forEach(b::writeUtf);
                    b.writeOptional(choice.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    label(b, choice.label());
                    b.writeOptional(choice.initial(), (out, initial) -> out.writeUtf(initial, TEXT));
                    b.writeOptional(choice.run(), (out, run) -> out.writeUtf(run, TEXT));
                    values(b, choice.enabledWhen());
                }
                case Card.Element.Number number -> {
                    b.writeByte(5);
                    b.writeUtf(number.param(), TEXT);
                    b.writeDouble(number.min());
                    b.writeDouble(number.max());
                    b.writeDouble(number.step());
                    b.writeOptional(number.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    label(b, number.label());
                    b.writeOptional(number.initial(), (out, initial) -> out.writeDouble(initial));
                    b.writeBoolean(number.typed());
                }
                case Card.Element.Flag flag -> {
                    b.writeByte(7);
                    b.writeUtf(flag.param(), TEXT);
                    b.writeOptional(flag.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    label(b, flag.label());
                    b.writeBoolean(flag.initial());
                }
                case Card.Element.Items items -> {
                    b.writeByte(8);
                    b.writeUtf(items.param(), TEXT);
                    b.writeOptional(items.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    label(b, items.label());
                    strings(b, items.initial().stream().map(ItemFilter::write).toList());
                    b.writeBoolean(items.single());
                    b.writeBoolean(items.blocks());
                }
                case Card.Element.Lines ignored -> b.writeByte(9);
                case Card.Element.Rows rows -> {
                    b.writeByte(10);
                    b.writeUtf(rows.source(), TEXT);
                    label(b, rows.whenEmpty());
                }
                case Card.Element.Heading heading -> {
                    b.writeByte(11);
                    label(b, Optional.of(heading.text()));
                    b.writeEnum(heading.layout());
                }
                case Card.Element.Note note -> {
                    b.writeByte(12);
                    label(b, Optional.of(note.text()));
                }
            }
        }
    }

    private static List<Card.Element> elements(RegistryFriendlyByteBuf b) {
        List<Card.Element> elements = new ArrayList<>();
        for (int n = count(b, MOST_ELEMENTS); n > 0; n--) {
            elements.add(switch (b.readByte()) {
                case 0 -> {
                    String routine = b.readUtf(TEXT);
                    var icon = b.readOptional(in -> in.readResourceLocation());
                    yield new Card.Element.Button(routine, icon, values(b), label(b));
                }
                case 1 -> new Card.Element.Toggle(b.readUtf(TEXT), b.readOptional(in -> in.readResourceLocation()));
                case 2 -> new Card.Element.Readout(b.readUtf(TEXT), b.readEnum(Card.Style.class), label(b));
                case 3 -> new Card.Element.Target(b.readUtf(TEXT), b.readEnum(Card.Pick.class),
                    b.readOptional(in -> in.readResourceLocation()), label(b), b.readBoolean(), values(b), values(b));
                case 4 -> {
                    String param = b.readUtf(TEXT);
                    var options = strings(b);
                    var labels = strings(b);
                    List<String> icons = new ArrayList<>();
                    for (int count = count(b, 256); count > 0; count--) {
                        icons.add(b.readUtf());
                    }
                    yield new Card.Element.Choice(param, options, labels, icons,
                        b.readOptional(in -> in.readResourceLocation()), label(b),
                        b.readOptional(in -> in.readUtf(TEXT)), b.readOptional(in -> in.readUtf(TEXT)), values(b));
                }
                case 5 -> new Card.Element.Number(b.readUtf(TEXT), b.readDouble(), b.readDouble(), b.readDouble(),
                    b.readOptional(in -> in.readResourceLocation()), label(b), b.readOptional(in -> in.readDouble()),
                    b.readBoolean());
                case 7 -> new Card.Element.Flag(b.readUtf(TEXT), b.readOptional(in -> in.readResourceLocation()),
                    label(b), b.readBoolean());
                case 8 -> new Card.Element.Items(b.readUtf(TEXT), b.readOptional(in -> in.readResourceLocation()),
                    label(b), strings(b).stream().flatMap(line -> ItemFilter.read(line).stream()).toList(),
                    b.readBoolean(), b.readBoolean());
                case 9 -> new Card.Element.Lines();
                case 10 -> new Card.Element.Rows(b.readUtf(TEXT), label(b));
                case 11 -> new Card.Element.Heading(label(b).orElseThrow(), b.readEnum(Card.Layout.class));
                case 12 -> new Card.Element.Note(label(b).orElseThrow());
                default -> throw new IllegalArgumentException("invalid card element");
            });
        }
        return elements;
    }

    public static void rows(RegistryFriendlyByteBuf b, List<Card.Row> rows) {
        b.writeVarInt(Math.min(rows.size(), MOST_ROWS));
        for (Card.Row row : rows.subList(0, Math.min(rows.size(), MOST_ROWS))) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(b, row.icon());
            Line.encodeAll(b, List.of(row.title()), 1);
            Line.encodeAll(b, row.details(), MOST_LINES);
            b.writeOptional(row.at(), (out, at) -> out.writeBlockPos(at));
            b.writeVarInt(row.acts().size());
            for (Card.Act act : row.acts()) {
                switch (act) {
                    case Card.Act.Run run -> {
                        b.writeByte(0);
                        b.writeUtf(run.routine(), TEXT);
                        values(b, run.arguments());
                        label(b, Optional.of(run.label()));
                        b.writeOptional(run.icon(), (out, icon) -> out.writeResourceLocation(icon));
                    }
                    case Card.Act.Open open -> {
                        b.writeByte(1);
                        b.writeUUID(open.facility());
                        label(b, Optional.of(open.label()));
                    }
                }
            }
        }
    }

    public static List<Card.Row> rows(RegistryFriendlyByteBuf b) {
        List<Card.Row> rows = new ArrayList<>();
        for (int n = count(b, MOST_ROWS); n > 0; n--) {
            ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(b);
            Line title = Line.decodeAll(b, 1).getFirst();
            List<Line> details = Line.decodeAll(b, MOST_LINES);
            var at = b.readOptional(in -> in.readBlockPos());
            List<Card.Act> acts = new ArrayList<>();
            for (int count = count(b, Card.Row.MOST_ACTS); count > 0; count--) {
                acts.add(switch (b.readByte()) {
                    case 0 -> new Card.Act.Run(b.readUtf(TEXT), values(b), label(b).orElseThrow(),
                        b.readOptional(in -> in.readResourceLocation()));
                    case 1 -> new Card.Act.Open(b.readUUID(), label(b).orElseThrow());
                    default -> throw new IllegalArgumentException("invalid card act");
                });
            }
            rows.add(new Card.Row(icon, title, details, at, acts));
        }
        return rows;
    }

    public static void values(RegistryFriendlyByteBuf b, Map<String, Value> values) {
        b.writeVarInt(values.size());
        values.forEach((name, value) -> {
            b.writeUtf(name, TEXT);
            value(b, Optional.of(value));
        });
    }

    public static Map<String, Value> values(RegistryFriendlyByteBuf b) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (int n = count(b, 256); n > 0; n--) {
            String name = b.readUtf(TEXT);
            values.put(name, value(b).orElseThrow(() -> new IllegalArgumentException("invalid card value")));
        }
        return values;
    }

    public static void value(RegistryFriendlyByteBuf b, Optional<Value> value) {
        switch (value.orElse(null)) {
            case null -> b.writeByte(0);
            case Value.Bool bool -> {
                b.writeByte(1);
                b.writeBoolean(bool.on());
            }
            case Value.Real real -> {
                b.writeByte(2);
                b.writeDouble(real.value());
            }
            case Value.Vector vector -> {
                b.writeByte(3);
                b.writeVarInt(vector.values().size());
                vector.values().forEach(b::writeDouble);
            }
            case Value.Pulse ignored -> b.writeByte(4);
            case Value.Data data -> {
                b.writeByte(5);
                b.writeNbt(data.tag());
            }
            case Value.Text text -> {
                b.writeByte(6);
                b.writeUtf(text.value(), MAX_VALUE_TEXT);
            }
            case Value.Sequence sequence -> {
                b.writeByte(7);
                b.writeNbt(sequence.save());
            }
            case Value.Items items -> {
                b.writeByte(8);
                b.writeNbt(items.save());
            }
        }
    }

    public static Optional<Value> value(RegistryFriendlyByteBuf b) {
        return switch (b.readByte()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(Value.of(b.readBoolean()));
            case 2 -> Optional.of(new Value.Real(b.readDouble()));
            case 3 -> {
                List<Double> values = new ArrayList<>();
                for (int n = count(b, 64); n > 0; n--) {
                    values.add(b.readDouble());
                }
                yield Optional.of(new Value.Vector(values));
            }
            case 4 -> Optional.of(Value.PULSE);
            case 5 -> {
                CompoundTag tag = b.readNbt();
                yield Optional.ofNullable(tag).map(Value.Data::new);
            }
            case 6 -> Optional.of(new Value.Text(b.readUtf(MAX_VALUE_TEXT)));
            case 7, 8 -> Optional.ofNullable(b.readNbt()).map(Value::load);
            default -> throw new IllegalArgumentException("invalid card value");
        };
    }

    private static void label(RegistryFriendlyByteBuf b, Optional<Card.Label> label) {
        b.writeOptional(label, (out, value) -> {
            out.writeUtf(value.text(), 512);
            out.writeBoolean(value.translated());
        });
    }

    private static Optional<Card.Label> label(RegistryFriendlyByteBuf b) {
        return b.readOptional(in -> new Card.Label(in.readUtf(512), in.readBoolean()));
    }

    private static void strings(RegistryFriendlyByteBuf b, List<String> values) {
        b.writeVarInt(values.size());
        values.forEach(value -> b.writeUtf(value, TEXT));
    }

    private static List<String> strings(RegistryFriendlyByteBuf b) {
        List<String> values = new ArrayList<>();
        for (int n = count(b, 256); n > 0; n--) {
            values.add(b.readUtf(TEXT));
        }
        return values;
    }

    private static int count(RegistryFriendlyByteBuf b, int max) {
        int count = b.readVarInt();
        if (count < 0 || count > max || count > b.readableBytes()) {
            throw new IllegalArgumentException("invalid card count");
        }
        return count;
    }
}
