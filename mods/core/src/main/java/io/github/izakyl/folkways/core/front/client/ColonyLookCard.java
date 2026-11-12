package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.api.notice.Meter;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.ui.Signs.Sign;
import io.github.izakyl.folkways.core.front.api.ui.Signs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ColonyLookCard {
    static final int TITLE_COLOR = 0xFFFFFFFF;
    static final int TEXT_COLOR = 0xFFAAAAAA;
    static final int WARN_COLOR = 0xFFFF5555;

    private ColonyLookCard() {
    }

    static List<Row> rows(List<Line> lines) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? TITLE_COLOR : TEXT_COLOR;
            switch (lines.get(i)) {
                case Line.Said said -> rows.add(new Row.Text((said.notice().component()).getString(), color));
                case Line.Literal literal -> rows.add(new Row.Text(literal.text(), color));
                case Line.Text text -> rows.add(text.after().isEmpty()
                    ? new Row.Text(text.component().getString(), text.component().getStyle().getColor() == null
                        ? color : 0xFF000000 | text.component().getStyle().getColor().getValue())
                    : new Row.Signs(named(text, color)));
                case Line.Blocked blocked -> rows.add(blocked(blocked));
                case Line.Told told -> rows.add(new Row.Signs(Signs.of(told.sentence(), color)));
                case Line.Busy busy -> {
                    Doing doing = busy.doing();
                    rows.add(new Row.Signs(Signs.of(Sentence.of(doing))));
                    doing.fraction(gameTime()).ifPresent(fill -> rows.add(new Row.Bar(fill)));
                }
                case Line.Gauge gauge ->
                    rows.add(new Row.Gauge(gauge.meter(), (float) gauge.value() / gauge.max()));
                case Line.Bar bar -> rows.add(new Row.Bar(bar.fill()));
                case Line.Goods goods -> goods(goods).ifPresent(rows::add);
                case Line.Contents contents -> contents(contents, rows);
            }
        }
        return rows;
    }

    private static List<Sign> named(Line.Text text, int color) {
        List<Sign> signs = new ArrayList<>();
        signs.add(new Sign.Word(text.component().getString(), color));
        signs.addAll(Signs.of(text.after(), color));
        return signs;
    }

    // What stands in the way, in words, then told in pictures when it is.
    private static Row blocked(Line.Blocked line) {
        String why = line.notice().component().getString();
        if (line.told().isEmpty()) {
            return new Row.Text(why, WARN_COLOR);
        }
        List<Sign> signs = new ArrayList<>();
        signs.add(new Sign.Word(why, WARN_COLOR));
        signs.addAll(Signs.of(line.told().get(), WARN_COLOR));
        return new Row.Signs(signs);
    }

    private static Optional<Row> goods(Line.Goods line) {
        List<ItemStack> stacks = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        for (Line.Stack stack : line.stacks()) {
            Item item = BuiltInRegistries.ITEM.get(stack.item());
            if (item == Items.AIR) {
                continue;
            }
            stacks.add(new ItemStack(item));
            counts.add(Signs.compact(stack.count()));
        }
        return stacks.isEmpty() ? Optional.empty() : Optional.of(new Row.Goods(stacks, counts));
    }

    // A store's goods in a grid of icons, then one quiet line for the kinds left out and the slots taken.
    private static void contents(Line.Contents line, List<Row> rows) {
        if (line.empty()) {
            rows.add(new Row.Text(Component.translatable("folkways.store.contents.empty").getString(), TEXT_COLOR));
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        List<String> counts = new ArrayList<>();
        int more = line.more();
        for (Line.Stack stack : line.stacks()) {
            Item item = BuiltInRegistries.ITEM.get(stack.item());
            if (item == Items.AIR) {
                more++;
                continue;
            }
            stacks.add(new ItemStack(item));
            counts.add(Signs.compact(stack.count()));
        }
        if (!stacks.isEmpty()) {
            rows.add(new Row.Grid(stacks, counts));
        }
        List<String> notes = new ArrayList<>();
        if (more > 0) {
            notes.add(Component.translatable("folkways.store.contents.more", more).getString());
        }
        if (line.total() > 0) {
            notes.add(Component.translatable("folkways.store.contents.room", line.used(), line.total()).getString());
        }
        if (!notes.isEmpty()) {
            rows.add(new Row.Text(String.join(" · ", notes), TEXT_COLOR));
        }
    }

    private static long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    sealed interface Row {

        record Text(String text, int color) implements Row {
        }

        record Bar(float fill) implements Row {
        }

        record Gauge(Meter meter, float fill) implements Row {
        }

        record Goods(List<ItemStack> stacks, List<String> counts) implements Row {
            public Goods {
                stacks = List.copyOf(stacks);
                counts = List.copyOf(counts);
            }
        }

        /** Goods laid in even columns, each icon with its count beside it. */
        record Grid(List<ItemStack> stacks, List<String> counts) implements Row {
            public Grid {
                stacks = List.copyOf(stacks);
                counts = List.copyOf(counts);
            }
        }

        record Signs(List<Sign> signs) implements Row {
            public Signs {
                signs = List.copyOf(signs);
            }
        }
    }
}
