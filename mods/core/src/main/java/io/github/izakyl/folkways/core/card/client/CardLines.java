package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Signs;
import io.github.izakyl.folkways.core.front.api.ui.Signs.Sign;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A facility's status lines on the page: the same lines its hover card tells in the world, inked on paper. Words and
 * pictures share one line each; a gauge or a bar is a meter.
 */
public final class CardLines {
    public static final int WARN = 0xFF9A3324;
    private static final int METER = 0xFF557A46;

    private CardLines() {
    }

    /** The line drawn in {@code ink}, or nothing for a line with nothing to show here. */
    public static List<UIElement> of(Line line, int ink) {
        List<UIElement> drawn = new ArrayList<>();
        switch (line) {
            case Line.Said said -> drawn.add(words(said.notice().component(), ink));
            case Line.Literal literal -> drawn.add(words(Component.literal(literal.text()), ink));
            case Line.Text text -> {
                if (text.after().isEmpty()) {
                    drawn.add(words(text.component(), text.component().getStyle().getColor() == null ? ink
                        : 0xFF000000 | text.component().getStyle().getColor().getValue()));
                } else {
                    List<Sign> signs = new ArrayList<>();
                    signs.add(new Sign.Word(text.component().getString(), ink));
                    signs.addAll(Signs.of(text.after(), ink));
                    drawn.add(new Pictures(signs));
                }
            }
            case Line.Blocked blocked -> {
                if (blocked.told().isEmpty()) {
                    drawn.add(words(blocked.notice().component(), WARN));
                } else {
                    List<Sign> signs = new ArrayList<>();
                    signs.add(new Sign.Word(blocked.notice().component().getString(), WARN));
                    signs.addAll(Signs.of(blocked.told().get(), WARN));
                    drawn.add(new Pictures(signs));
                }
            }
            case Line.Told told -> drawn.add(new Pictures(Signs.of(told.sentence(), ink)));
            case Line.Busy busy -> {
                drawn.add(new Pictures(Signs.of(Sentence.of(busy.doing()), ink)));
                busy.doing().fraction(gameTime()).ifPresent(fill -> drawn.add(meter(fill, METER)));
            }
            case Line.Gauge gauge -> drawn.add(meter((float) gauge.value() / gauge.max(),
                0xFF000000 | gauge.meter().color()));
            case Line.Bar bar -> drawn.add(meter(bar.fill(), METER));
            case Line.Goods goods -> {
                List<Sign> wares = wares(goods.stacks(), new int[1]);
                if (!wares.isEmpty()) {
                    drawn.add(new Pictures(wares));
                }
            }
            case Line.Contents contents -> {
                if (contents.empty()) {
                    drawn.add(words(Component.translatable("folkways.store.contents.empty"), ink));
                    break;
                }
                int[] unknown = new int[1];
                List<Sign> wares = wares(contents.stacks(), unknown);
                if (!wares.isEmpty()) {
                    drawn.add(new Pictures(wares));
                }
                List<String> notes = new ArrayList<>();
                int more = contents.more() + unknown[0];
                if (more > 0) {
                    notes.add(Component.translatable("folkways.store.contents.more", more).getString());
                }
                if (contents.total() > 0) {
                    notes.add(Component.translatable("folkways.store.contents.room", contents.used(), contents.total())
                        .getString());
                }
                if (!notes.isEmpty()) {
                    drawn.add(words(Component.literal(String.join(" · ", notes)), Rows.QUIET));
                }
            }
        }
        return drawn;
    }

    /** The line's words alone, for a title or a tooltip. */
    public static Component words(Line line) {
        return switch (line) {
            case Line.Said said -> said.notice().component();
            case Line.Literal literal -> Component.literal(literal.text());
            case Line.Text text -> text.component();
            case Line.Blocked blocked -> blocked.notice().component();
            default -> Component.empty();
        };
    }

    private static List<Sign> wares(List<Line.Stack> stacks, int[] unknown) {
        List<Sign> wares = new ArrayList<>();
        for (Line.Stack stack : stacks) {
            Item item = BuiltInRegistries.ITEM.get(stack.item());
            if (item == Items.AIR) {
                unknown[0]++;
                continue;
            }
            wares.add(new Sign.Ware(new ItemStack(item), stack.count() > 1 ? Signs.compact(stack.count()) : ""));
        }
        return wares;
    }

    private static UIElement words(Component text, int color) {
        var label = Rows.text(text);
        label.textStyle(style -> style.textColor(color));
        return label;
    }

    private static UIElement meter(float fill, int color) {
        UIElement meter = Rows.meter();
        Rows.meterFill(meter, fill * 100, color);
        meter.layout(layout -> layout.widthPercent(100).flexGrow(0).alignSelf(
            dev.vfyjxf.taffy.style.AlignItems.FLEX_START));
        return meter;
    }

    private static long gameTime() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /** Words and pictures in one line, laid in the room the line is given; what does not fit gives way to "…". */
    private static final class Pictures extends UIElement {
        private final List<Sign> signs;

        Pictures(List<Sign> signs) {
            this.signs = List.copyOf(signs);
            layout(layout -> layout.widthPercent(100).height(Signs.HEIGHT).flexShrink(0));
            setOverflowVisible(false);
        }

        @Override
        public void drawBackgroundAdditional(GUIContext context) {
            super.drawBackgroundAdditional(context);
            var font = Minecraft.getInstance().font;
            int room = Math.max(0, Math.round(getSizeWidth()));
            var graphics = context.graphics;
            graphics.flush();
            Signs.draw(graphics, font, Signs.lay(font, signs, room), Math.round(getPositionX()),
                Math.round(getPositionY()));
            graphics.flush();
        }
    }
}
