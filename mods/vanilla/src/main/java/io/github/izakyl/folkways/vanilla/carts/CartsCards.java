package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.shell.carts.api.CartContents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The book shows a cart's synchronized stock even when no resident walks alongside it. */
public final class CartsCards {
    private CartsCards() {
    }

    /** Entity ids queued by the last world frame, for read-only capture inspection. */
    public static List<Integer> shown() {
        return CartsCardRenderer.shown();
    }

    static List<Line> lines(AbstractCargoCart cart) {
        CompoundTag contents = ((CartContents.View) cart).folkways$contents();
        List<Line.Stack> stacks = contents.getAllKeys().stream()
            .map(item -> new Line.Stack(ResourceLocation.parse(item), contents.getLong(item)))
            .filter(stack -> stack.count() > 0)
            .sorted(Comparator.comparingLong(Line.Stack::count).reversed()
                .thenComparing(stack -> stack.item().toString())).toList();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.text(cart.getDisplayName()));
        if (stacks.isEmpty()) {
            lines.add(Line.text(Component.translatable("folkways.draft.empty")));
        }
        for (int at = 0; at < stacks.size(); at += Line.Goods.MOST_STACKS) {
            lines.add(Line.goods(stacks.subList(at, Math.min(at + Line.Goods.MOST_STACKS, stacks.size()))));
        }
        return List.copyOf(lines);
    }
}
