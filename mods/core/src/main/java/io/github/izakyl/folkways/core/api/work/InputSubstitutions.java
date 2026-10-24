package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Alternatives for declared consumable inputs; ordinary goods identity and deliveries stay exact. */
public final class InputSubstitutions {
    public record Rule(ResourceLocation id, ItemSpec targets, ItemSpec payment, ItemSpec fallback, int cost) {
        public Rule {
            if (cost < 1 || cost > 64 || payment.item().isEmpty() || fallback.item().isEmpty()) {
                throw new IllegalArgumentException("substitutions need concrete payment/fallback and a cost from 1 to 64");
            }
        }
    }

    public record Choice(ItemSpec payment, ItemSpec target, int cost) { }

    private static final Map<ResourceLocation, List<Rule>> owners = new LinkedHashMap<>();
    private static volatile long revision;
    private static volatile List<Rule> rules = List.of();

    private InputSubstitutions() { }

    public static long revision() { return revision; }

    public static synchronized void replace(ResourceLocation owner, List<Rule> replacement) {
        if (replacement.isEmpty()) owners.remove(owner);
        else owners.put(owner, List.copyOf(replacement));
        rules = owners.values().stream().flatMap(List::stream).sorted(Comparator.comparing(Rule::id)).toList();
        revision++;
    }

    public static List<Choice> choices(Need need) {
        if (!need.substitutable()) return List.of();
        List<Choice> choices = new ArrayList<>();
        for (Rule rule : rules) {
            ItemStack payment = Goods.display(rule.payment());
            if (payment.isEmpty() || Goods.matches(payment, rule.targets())) continue;
            ItemStack target = Goods.display(need.spec());
            ItemStack fallback = Goods.display(rule.fallback());
            if (Goods.matches(fallback, need.spec()) && Goods.matches(fallback, rule.targets())) target = fallback;
            if (!Goods.matches(target, rule.targets())) {
                target = Goods.members(need.spec()).stream().sorted(Comparator.comparing(item ->
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)))
                    .map(net.minecraft.world.item.Item::getDefaultInstance)
                    .filter(stack -> Goods.matches(stack, need.spec()) && Goods.matches(stack, rule.targets()))
                    .findFirst().orElse(ItemStack.EMPTY);
            }
            if (!Goods.matches(target, need.spec()) || !Goods.matches(target, rule.targets())
                    || !ItemStack.isSameItemSameComponents(target, target.getItem().getDefaultInstance())) continue;
            // A category cannot invent names, contents, enchantments or other component identities.
            choices.add(new Choice(rule.payment(), Goods.specOf(target), rule.cost()));
        }
        return List.copyOf(choices);
    }
}
