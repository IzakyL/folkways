package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

public final class LoadoutRegistry {
    private static volatile Map<ResourceLocation, LoadoutProvider> providers = Map.of();

    static {
        register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "melee"),
            new Ordinary("melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS), false));
        register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "bow"),
            new Ordinary("bow", ItemSpec.of(Items.BOW), true));
        register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "crossbow"),
            new Ordinary("crossbow", ItemSpec.of(Items.CROSSBOW), true));
    }

    private LoadoutRegistry() {
    }

    public static synchronized void register(ResourceLocation id, LoadoutProvider provider) {
        if (providers.containsKey(id)) {
            throw new IllegalArgumentException("loadout already registered: " + id);
        }
        Map<ResourceLocation, LoadoutProvider> next = new LinkedHashMap<>();
        next.put(Objects.requireNonNull(id), Objects.requireNonNull(provider));
        next.putAll(providers);
        providers = Collections.unmodifiableMap(next);
    }

    public static Optional<LoadoutProvider> find(ResourceLocation id) {
        return Optional.ofNullable(providers.get(id));
    }

    public static Map<ResourceLocation, LoadoutProvider> entries() {
        return providers;
    }

    public static Optional<LoadoutProvider> using(ItemStack stack) {
        return providers.values().stream().filter(provider -> provider.accepts(stack)).findFirst();
    }

    public static List<ItemStack> carried(ResidentEntity body) {
        List<ItemStack> stacks = new ArrayList<>();
        if (body.hasWeaponSession()) {
            stacks.add(body.getMainHandItem());
        }
        stacks.addAll(body.pack().contents());
        return List.copyOf(stacks);
    }

    private record Ordinary(String name, ItemSpec weapon, boolean ranged) implements LoadoutProvider {

        @Override
        public boolean accepts(ItemStack stack) {
            return Goods.matches(stack, weapon);
        }

        @Override
        public boolean ranged(ItemStack stack) {
            return ranged;
        }

        @Override
        public double range(ItemStack stack) {
            return name.equals("bow") ? 15 : ranged ? 8 : 4.5;
        }

        @Override
        public int cycleTicks(ItemStack stack) {
            return name.equals("crossbow") ? 25 : ranged ? 20 : 10;
        }

        @Override
        public Optional<ItemSpec> ammunition(ItemStack stack) {
            return ranged ? Optional.of(ItemSpec.of(ItemTags.ARROWS)) : Optional.empty();
        }

        @Override
        public List<ItemStack> loadedAmmunition(ItemStack stack) {
            var charged = stack.get(DataComponents.CHARGED_PROJECTILES);
            return charged == null ? List.of() : charged.getItems().stream().map(ItemStack::copy).toList();
        }

        @Override
        public int roundsLoaded(ItemStack stack, ItemSpec ammunition) {
            var charged = stack.get(DataComponents.CHARGED_PROJECTILES);
            return charged == null
                ? 0
                : charged.getItems().stream()
                .filter(item -> Goods.matches(item, ammunition))
                .mapToInt(ItemStack::getCount)
                .sum();
        }
    }
}
