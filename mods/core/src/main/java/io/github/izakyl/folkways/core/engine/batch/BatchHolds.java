package io.github.izakyl.folkways.core.engine.batch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

public final class BatchHolds {
    private BatchHolds() { }

    public static Known exclude(Known known, List<ReservedInput> holds) {
        if (holds.isEmpty()) return known;
        Map<Stash, List<Need>> stores = new LinkedHashMap<>();
        Map<UUID, List<Need>> packs = new LinkedHashMap<>();
        for (ReservedInput hold : holds) {
            Need need = new Need(hold.goods(), hold.count());
            hold.store().ifPresent(at -> stores.computeIfAbsent(Stash.at(at), ignored -> new ArrayList<>()).add(need));
            hold.resident().ifPresent(who -> packs.computeIfAbsent(who, ignored -> new ArrayList<>()).add(need));
        }
        Situation before = known.world();
        Situation world = new Situation(before.ways(), before.stock().withheld(stores), before.workshops(), before.stands());
        List<Crew.Hand> hands = known.hands().stream().map(hand -> {
            List<Need> held = packs.getOrDefault(hand.id(), List.of());
            if (held.isEmpty()) return hand;
            // Kit and cargo are disjoint. Their capacities stay those of the physical, unfiltered pack.
            long[] left = held.stream().mapToLong(Need::count).toArray();
            List<ItemStack> cargo = exclude(hand.cargo(), held, left);
            List<ItemStack> tools = exclude(hand.tools(), held, left);
            return new Crew.Hand(hand.who(), hand.at(), hand.packCells(), hand.packCapacity(), hand.pace(),
                hand.licences(), tools, hand.holding(), cargo);
        }).toList();
        return new Known(world, hands);
    }

    private static List<ItemStack> exclude(List<ItemStack> stacks, List<Need> held, long[] left) {
        List<ItemStack> available = new ArrayList<>();
        for (ItemStack original : stacks) {
            ItemStack stack = original.copy();
            for (int i = 0; i < held.size() && !stack.isEmpty(); i++) {
                if (!Goods.matches(stack, held.get(i).spec())) continue;
                int take = (int) Math.min(left[i], stack.getCount());
                stack.shrink(take);
                left[i] -= take;
            }
            if (!stack.isEmpty()) available.add(stack);
        }
        return List.copyOf(available);
    }
}
