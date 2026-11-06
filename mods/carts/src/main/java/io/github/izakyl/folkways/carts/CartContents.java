package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.front.api.notice.Line;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Exact stock, independent of Redux's deliberately approximate cargo models and the open inventory menu. */
public final class CartContents {
    public interface View {
        CompoundTag folkways$contents();

        void folkways$contents(CompoundTag contents);
    }

    private CartContents() {
    }

    public static void synchronize(AbstractCargoCart cart) {
        if (cart.level().isClientSide()) {
            return;
        }
        CompoundTag contents = new CompoundTag();
        for (var stack : cart.getItemStacks()) {
            if (!stack.isEmpty()) {
                String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                contents.putLong(item, contents.getLong(item) + stack.getCount());
            }
        }
        ((View) cart).folkways$contents(contents);
    }

    public static List<Line> lines(AbstractCargoCart cart) {
        CompoundTag contents = ((View) cart).folkways$contents();
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
