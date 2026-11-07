package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.resident.api.TaskTools;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Kit {

    private final Set<ItemSpec> specs;
    private final List<Amount> kept;
    private final Predicate<ItemStack> borrowed;

    private Kit(Set<ItemSpec> specs, List<Amount> kept, Predicate<ItemStack> borrowed) {
        this.specs = specs;
        this.kept = kept;
        this.borrowed = borrowed;
    }

    public static Kit of(Colony colony, Body body) {
        Licences can = body.licences();
        Set<ItemSpec> wanted = new LinkedHashSet<>();
        for (Vocation vocation : Vocations.all()) {
            if (can.of(vocation).isPresent() && !body.kind().worksBarehanded(vocation.id())) {
                for (ItemSpec tool : vocation.kit()) {
                    allowed(colony, vocation, tool).ifPresent(wanted::add);
                }
            }
        }
        List<Amount> kept = new ArrayList<>(PackKeeps.of(colony, body));
        for (Equipment.Demand clothing : Equipment.wanted(colony, body)) {
            if (!Goods.matches(body.mob().getItemBySlot(clothing.slot()), clothing.items())) {
                kept.add(new Amount(clothing.items(), 1));
            }
        }
        return new Kit(Set.copyOf(wanted), List.copyOf(kept), stack -> TaskTools.holds(body.id(), stack));
    }

    // The part of a kit's tool the colony lets its residents take up; nothing, when it allows none of it.
    private static Optional<ItemSpec> allowed(Colony colony, Vocation vocation, ItemSpec tool) {
        Set<Item> named = new LinkedHashSet<>();
        collect(tool, named);
        List<ItemSpec> kept = named.stream()
            .filter(item -> KitRules.allows(colony, vocation, item)
                && !io.github.izakyl.folkways.core.work.support.Tools.sharedItem(item.getDefaultInstance()))
            .map(item -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)))
            .toList();
        if (kept.size() == named.size()) {
            return Optional.of(tool);
        }
        return kept.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(kept));
    }

    private static void collect(ItemSpec tool, Set<Item> into) {
        tool.item().map(BuiltInRegistries.ITEM::get).ifPresent(into::add);
        tool.tag().ifPresent(tag -> BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(held -> into.add(held.value())));
        tool.anyOf().forEach(one -> collect(one, into));
    }

    public List<ItemSpec> specs() {
        return List.copyOf(specs);
    }

    public static long carried(Body body, ItemSpec spec) {
        return Goods.countIn(body.pack(), spec);
    }

    public boolean holds(ItemStack stack) {
        if (stack.isEmpty() || borrowed.test(stack)) {
            return true;
        }
        for (ItemSpec spec : specs) {
            if (Goods.matches(stack, spec)) {
                return true;
            }
        }
        return false;
    }

    public List<ItemStack> inPack(Body body) {
        return inPack(body.pack().contents());
    }

    public List<ItemStack> inPack(List<ItemStack> contents) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (holds(stack)) {
                found.add(stack.copy());
            }
        }
        return List.copyOf(found);
    }

    // What the pack holds beyond its tools and what the resident keeps there: the goods the plan may put away or
    // spend. What is kept is counted off the first stacks that match it.
    public List<ItemStack> cargoIn(Body body) {
        return cargoIn(body.pack().contents());
    }

    public List<ItemStack> cargoIn(List<ItemStack> contents) {
        List<ItemStack> found = new ArrayList<>();
        split(contents, (stack, keeps) -> {
            if (keeps < stack.getCount()) {
                found.add(stack.copyWithCount(stack.getCount() - keeps));
            }
        });
        return List.copyOf(found);
    }

    // The pack cells what the resident keeps takes up. They never empty, so the plan counts the pack without them:
    // a pack it thinks will empty when nothing in it can be put away leaves work waiting on room that never comes.
    public int keptCells(Body body) {
        return keptCells(body.pack().contents());
    }

    public int keptCells(List<ItemStack> contents) {
        int[] cells = {0};
        split(contents, (stack, keeps) -> {
            if (keeps > 0) {
                cells[0]++;
            }
        });
        return cells[0];
    }

    // Each stack beyond the tools, with how much of it is kept.
    private void split(List<ItemStack> contents, ObjIntConsumer<ItemStack> each) {
        long[] left = kept.stream().mapToLong(Amount::count).toArray();
        for (ItemStack stack : contents) {
            if (holds(stack)) {
                continue;
            }
            int keeps = 0;
            for (int at = 0; at < left.length && keeps < stack.getCount(); at++) {
                if (left[at] > 0 && Goods.matches(stack, kept.get(at).spec())) {
                    int off = (int) Math.min(left[at], stack.getCount() - keeps);
                    left[at] -= off;
                    keeps += off;
                }
            }
            each.accept(stack, keeps);
        }
    }
}
