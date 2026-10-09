package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Meter;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.ui.Signs;
import io.github.izakyl.folkways.front.api.ui.Signs.Sign;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
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
            }
        }
        return rows;
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

        record Signs(List<Sign> signs) implements Row {
            public Signs {
                signs = List.copyOf(signs);
            }
        }
    }
}
