package io.github.izakyl.folkways.core.api.terms;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

// How the colony may use one of its stores. Rules on the same store from different owners all hold at once.
public sealed interface StoreRule {

    WorldPos where();

    // Goods kept stocked here. They feed any other need, but never another reserve of the same goods, so two
    // reserves cannot empty into each other.
    record Reserve(WorldPos where, ItemSpec goods) implements StoreRule {

        public Reserve {
            Objects.requireNonNull(where, "where");
            Objects.requireNonNull(goods, "goods");
        }
    }

    // What residents may put here: only these goods, or anything but them. What is already inside may still be
    // taken out.
    record Admit(WorldPos where, boolean only, List<ItemSpec> goods) implements StoreRule {

        public Admit {
            Objects.requireNonNull(where, "where");
            goods = List.copyOf(goods);
        }

        public boolean admits(ItemStack stack) {
            boolean listed = goods.stream().anyMatch(spec -> Goods.matches(stack, spec));
            return listed == only;
        }

        public boolean admits(ItemSpec spec) {
            if (only) {
                return Goods.members(spec).stream()
                    .allMatch(item -> goods.stream().anyMatch(listed -> Goods.members(listed).contains(item)));
            }
            return goods.stream().noneMatch(listed -> Goods.overlap(listed, spec));
        }
    }

    // The slots residents may reach into, counted from zero, `to` exclusive.
    record Slots(WorldPos where, int from, int to) implements StoreRule {

        public Slots {
            Objects.requireNonNull(where, "where");
            if (from < 0 || to <= from) {
                throw new IllegalArgumentException("slots " + from + ".." + to + " hold nothing");
            }
        }
    }

    static boolean admits(List<StoreRule> rules, ItemStack stack) {
        return all(rules, admit -> admit.admits(stack));
    }

    static boolean admits(List<StoreRule> rules, ItemSpec spec) {
        return all(rules, admit -> admit.admits(spec));
    }

    static boolean reserves(List<StoreRule> rules, ItemSpec spec) {
        for (StoreRule rule : rules) {
            if (rule instanceof Reserve reserve && Goods.overlap(reserve.goods(), spec)) {
                return true;
            }
        }
        return false;
    }

    private static boolean all(List<StoreRule> rules, Predicate<Admit> test) {
        for (StoreRule rule : rules) {
            if (rule instanceof Admit admit && !test.test(admit)) {
                return false;
            }
        }
        return true;
    }
}
