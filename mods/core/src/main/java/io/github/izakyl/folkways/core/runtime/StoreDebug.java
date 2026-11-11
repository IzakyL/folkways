package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.inventory.api.ColonyInventory;
import io.github.izakyl.folkways.core.inventory.internal.InventoryIndex;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.resident.api.Doings;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record StoreDebug(boolean available, List<Difference> differences) {
    public record Difference(Item item, long cached, long live) {
    }

    public StoreDebug {
        differences = List.copyOf(differences);
    }

    public static StoreDebug read(ServerLevel level, InventorySnapshot.Store store,
                                  ColonyInventory inventory) {
        if (!(inventory instanceof InventoryIndex index)) {
            return new StoreDebug(false, List.of());
        }
        var contents = index.debugContents(level, store.stash());
        if (contents.isEmpty()) {
            return new StoreDebug(false, List.of());
        }
        Map<Item, Long> cached = new LinkedHashMap<>();
        Map<Item, Long> actual = new LinkedHashMap<>();
        for (SlotSnapshot slot : store.slots()) {
            count(cached, slot.stackCopy());
        }
        for (ItemStack stack : contents.get()) {
            count(actual, stack);
        }
        List<Difference> differences = new ArrayList<>();
        var items = new LinkedHashSet<>(cached.keySet());
        items.addAll(actual.keySet());
        for (Item item : items) {
            long before = cached.getOrDefault(item, 0L);
            long now = actual.getOrDefault(item, 0L);
            if (before != now) {
                differences.add(new Difference(item, before, now));
            }
        }
        return new StoreDebug(true, differences);
    }

    public static List<Line> lines(ServerLevel level, InventorySnapshot.Store one, ColonyInventory inventory) {
        return lines(level, one, inventory, List.of());
    }

    /** The store's card, its cached contents split by the lot and space claims the plan holds on it. */
    static List<Line> lines(ServerLevel level, InventorySnapshot.Store one, ColonyInventory inventory,
                            List<Claim> claims) {
        List<Line> lines = new ArrayList<>();
        lines.add(DebugText.section("store"));
        var version = one.version();
        lines.add(debugLine("store", word(one.state().name()), one.incarnation(),
            text("version", version.contents(), version.policy())));
        lines.addAll(DebugGoods.stored(one.slots().stream().map(SlotSnapshot::stackCopy).toList(), claims));
        for (StoreRule rule : one.rules()) {
            Component detail = switch (rule) {
                case StoreRule.Reserve reserve -> text("reserve", goods(reserve.goods()));
                case StoreRule.Admit admit -> text("admit", admit.only(),
                    ComponentUtils.formatList(admit.goods(), StoreDebug::goods));
                case StoreRule.Prefer prefer -> text("prefer", goods(prefer.goods()), prefer.priority());
                case StoreRule.Access access -> text("access", access.take(), access.store());
                case StoreRule.Slots slots -> text("slots", slots.from(), slots.to());
            };
            lines.add(Line.text(detail));
        }
        if (!one.refused().isEmpty()) {
            lines.add(debugLine("refused", ComponentUtils.formatList(one.refused().stream()
                .map(item -> DebugText.tone(item.getDescription(), DebugText.BAD)).toList(),
                component -> component)));
        }
        StoreDebug comparison = StoreDebug.read(level, one, inventory);
        lines.add(debugLine("comparison", word(!comparison.available() ? "unavailable"
            : comparison.differences().isEmpty() ? "matches" : "differs")));
        comparison.differences().stream().limit(8).forEach(diff -> lines.add(debugLine("difference",
            DebugText.item(diff.item().getDescription()), diff.cached(), diff.live())));
        if (comparison.differences().size() > 8) {
            lines.add(debugLine("more", comparison.differences().size() - 8));
        }
        return List.copyOf(lines);
    }

    private static Component goods(ItemSpec spec) {
        return DebugText.item(Doings.about(spec).<Component>map(Component::translatable)
            .orElseGet(() -> Component.literal(spec.describe())));
    }

    private static Component word(String key) {
        return DebugText.word(key);
    }

    private static Component text(String key, Object... args) {
        return DebugText.text(key, args);
    }

    private static Line debugLine(String key, Object... args) {
        return DebugText.line(key, args);
    }

    private static void count(Map<Item, Long> counts, ItemStack stack) {
        if (!stack.isEmpty()) {
            counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
        }
    }
}
