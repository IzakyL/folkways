package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Licences;
import io.github.izakyl.folkways.core.api.resident.body.PackKeeps;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class Kit {

    private final Set<ItemSpec> specs;
    private final List<Amount> kept;

    private Kit(Set<ItemSpec> specs, List<Amount> kept) {
        this.specs = specs;
        this.kept = kept;
    }

    static Kit of(Colony colony, Body body) {
        Licences can = body.licences();
        Set<ItemSpec> wanted = new LinkedHashSet<>();
        for (Vocation vocation : Vocations.all()) {
            if (can.of(vocation).isPresent() && !body.kind().worksBarehanded(vocation.id())) {
                for (ItemSpec tool : vocation.kit()) {
                    allowed(colony, tool).ifPresent(wanted::add);
                }
            }
        }
        return new Kit(Set.copyOf(wanted), PackKeeps.of(colony, body));
    }

    // The part of a kit's tool the colony lets its residents take up; nothing, when it allows none of it.
    private static Optional<ItemSpec> allowed(Colony colony, ItemSpec tool) {
        Set<Item> named = new LinkedHashSet<>();
        collect(tool, named);
        List<ItemSpec> kept = named.stream()
            .filter(item -> KitRules.allows(colony, item))
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

    List<ItemSpec> specs() {
        return List.copyOf(specs);
    }

    static long carried(Body body, ItemSpec spec) {
        return Goods.countIn(body.pack(), spec);
    }

    boolean holds(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        for (ItemSpec spec : specs) {
            if (Goods.matches(stack, spec)) {
                return true;
            }
        }
        return false;
    }

    List<ItemStack> inPack(Body body) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack stack : body.pack().contents()) {
            if (holds(stack)) {
                found.add(stack.copy());
            }
        }
        return List.copyOf(found);
    }

    // What the pack holds beyond its tools and what the resident keeps there: the goods the plan may put away or
    // spend. What is kept is counted off the first stacks that match it.
    List<ItemStack> cargoIn(Body body) {
        List<ItemStack> found = new ArrayList<>();
        split(body, (stack, keeps) -> {
            if (keeps < stack.getCount()) {
                found.add(stack.copyWithCount(stack.getCount() - keeps));
            }
        });
        return List.copyOf(found);
    }

    // The pack cells what the resident keeps takes up. They never empty, so the plan counts the pack without them:
    // a pack it thinks will empty when nothing in it can be put away leaves work waiting on room that never comes.
    int keptCells(Body body) {
        int[] cells = {0};
        split(body, (stack, keeps) -> {
            if (keeps > 0) {
                cells[0]++;
            }
        });
        return cells[0];
    }

    // Each stack beyond the tools, with how much of it is kept.
    private void split(Body body, ObjIntConsumer<ItemStack> each) {
        long[] left = kept.stream().mapToLong(Amount::count).toArray();
        for (ItemStack stack : body.pack().contents()) {
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
