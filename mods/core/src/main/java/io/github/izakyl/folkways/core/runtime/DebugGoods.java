package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Pack and store contents split by what the plan and the resident make of them, one tinted row per state. */
final class DebugGoods {
    private DebugGoods() {
    }

    /**
     * A resident's pack: what it keeps for itself (its kit and keeps), the cargo the plan has given to its work, and
     * the cargo nobody has, which waits to be put away.
     */
    static List<Line> carrying(List<ItemStack> pack, List<ItemStack> cargo, List<Claim> planned) {
        Map<Item, Long> own = count(pack);
        Map<Item, Long> loose = count(cargo);
        loose.forEach((item, amount) -> own.merge(item, -amount, Long::sum));
        Map<Item, Long> claimed = take(cargo, planned, claim -> claim.on() instanceof Resource.Cargo one ? one.spec()
            : claim.on() instanceof Resource.Handed one ? one.spec() : null);
        claimed.forEach((item, amount) -> loose.merge(item, -amount, Long::sum));
        List<Line> lines = new ArrayList<>();
        rows(lines, own, DebugText.OWN);
        rows(lines, claimed, DebugText.PLANNED);
        rows(lines, loose, DebugText.LOOSE);
        if (!lines.isEmpty()) {
            lines.add(legend(List.of(DebugText.OWN, DebugText.PLANNED, DebugText.LOOSE),
                List.of("own", "planned", "loose")));
        }
        return lines;
    }

    /**
     * A store: the stock the plan will draw (lot claims), the stock nobody has asked for, and the room the plan has
     * set aside for goods still on their way (space claims).
     */
    static List<Line> stored(List<ItemStack> contents, List<Claim> claims) {
        Map<Item, Long> free = count(contents);
        Map<Item, Long> drawn = take(contents, claims,
            claim -> claim.on() instanceof Resource.Lot lot ? lot.spec() : null);
        drawn.forEach((item, amount) -> free.merge(item, -amount, Long::sum));
        Map<Item, Long> room = new LinkedHashMap<>();
        for (Claim claim : claims) {
            if (claim.on() instanceof Resource.Space space) {
                example(space.spec()).ifPresent(item -> room.merge(item, claim.amount(), Long::sum));
            }
        }
        List<Line> lines = new ArrayList<>();
        rows(lines, drawn, DebugText.PLANNED);
        rows(lines, free, DebugText.LOOSE);
        rows(lines, room, DebugText.SPACE);
        if (!lines.isEmpty()) {
            lines.add(legend(List.of(DebugText.PLANNED, DebugText.LOOSE, DebugText.SPACE),
                List.of("drawn", "free", "space")));
        }
        return lines;
    }

    // How much of the goods the claims cover, matched stack by stack and never more than is there.
    private static Map<Item, Long> take(List<ItemStack> goods, List<Claim> claims,
                                        java.util.function.Function<Claim, ItemSpec> spec) {
        List<ItemSpec> specs = new ArrayList<>();
        List<long[]> left = new ArrayList<>();
        for (Claim claim : claims) {
            ItemSpec one = spec.apply(claim);
            if (one != null) {
                specs.add(one);
                left.add(new long[] {claim.amount()});
            }
        }
        Map<Item, Long> taken = new LinkedHashMap<>();
        for (ItemStack stack : goods) {
            long there = stack.getCount();
            for (int at = 0; at < specs.size() && there > 0; at++) {
                if (left.get(at)[0] > 0 && Goods.matches(stack, specs.get(at))) {
                    long off = Math.min(there, left.get(at)[0]);
                    left.get(at)[0] -= off;
                    there -= off;
                    taken.merge(stack.getItem(), off, Long::sum);
                }
            }
        }
        return taken;
    }

    private static Map<Item, Long> count(List<ItemStack> stacks) {
        Map<Item, Long> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
            }
        }
        return counts;
    }

    private static void rows(List<Line> lines, Map<Item, Long> counts, int tint) {
        List<Line.Stack> stacks = counts.entrySet().stream().filter(entry -> entry.getValue() > 0)
            .map(entry -> new Line.Stack(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue())).toList();
        for (int from = 0; from < stacks.size(); from += Line.Goods.MOST_STACKS) {
            lines.add(Line.goods(stacks.subList(from, Math.min(from + Line.Goods.MOST_STACKS, stacks.size())), tint));
        }
    }

    private static Line legend(List<Integer> tints, List<String> keys) {
        List<Component> parts = new ArrayList<>();
        for (int at = 0; at < tints.size(); at++) {
            parts.add(Component.literal("■ ").withColor(tints.get(at)).append(DebugText.text("legend." + keys.get(at))));
        }
        return Line.text(DebugText.join(parts));
    }

    // The item a spec is drawn as: its own, else the first of its tag or alternatives.
    private static Optional<Item> example(ItemSpec spec) {
        Optional<ResourceLocation> id = spec.item();
        if (id.isPresent()) {
            Item item = BuiltInRegistries.ITEM.get(id.get());
            return item == Items.AIR ? Optional.empty() : Optional.of(item);
        }
        if (spec.tag().isPresent()) {
            for (var held : BuiltInRegistries.ITEM.getTagOrEmpty(spec.tag().get())) {
                return Optional.of(held.value());
            }
        }
        for (ItemSpec one : spec.anyOf()) {
            Optional<Item> found = example(one);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
