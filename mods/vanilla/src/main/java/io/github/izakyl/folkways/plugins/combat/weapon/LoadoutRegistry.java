package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

public final class LoadoutRegistry {
    public record Selection(ResourceLocation id, CompoundTag options) {
        public Selection { java.util.Objects.requireNonNull(id); options = options.copy(); }
        @Override public CompoundTag options() { return options.copy(); }
        public Optional<LoadoutProvider> provider() { return find(id).filter(provider -> provider.valid(options())); }
    }
    public static final Selection MELEE = selection("melee");
    public static final Selection BOW = selection("bow");
    public static final Selection CROSSBOW = selection("crossbow");
    private static volatile Map<ResourceLocation, LoadoutProvider> providers = Map.of();
    static {
        register(MELEE.id(), new Ordinary("melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS), false));
        register(BOW.id(), new Ordinary("bow", ItemSpec.of(Items.BOW), true));
        register(CROSSBOW.id(), new Ordinary("crossbow", ItemSpec.of(Items.CROSSBOW), true));
    }
    private LoadoutRegistry() { }
    private static Selection selection(String path) {
        return new Selection(ResourceLocation.fromNamespaceAndPath("folkways", path), new CompoundTag());
    }
    public static synchronized void register(ResourceLocation id, LoadoutProvider provider) {
        if (providers.containsKey(id)) { throw new IllegalArgumentException("loadout already registered: " + id); }
        Map<ResourceLocation, LoadoutProvider> next = new LinkedHashMap<>(providers);
        next.put(java.util.Objects.requireNonNull(id), java.util.Objects.requireNonNull(provider));
        providers = java.util.Collections.unmodifiableMap(next);
    }
    public static Optional<LoadoutProvider> find(ResourceLocation id) { return Optional.ofNullable(providers.get(id)); }
    public static Map<ResourceLocation, LoadoutProvider> entries() { return providers; }
    public static Optional<Selection> infer(List<ItemStack> weapons) {
        for (ItemStack stack : weapons) {
            for (var entry : providers.entrySet()) {
                Optional<CompoundTag> options = entry.getValue().infer(stack).filter(entry.getValue()::valid);
                if (options.isPresent()) { return Optional.of(new Selection(entry.getKey(), options.get())); }
            }
        }
        return Optional.empty();
    }
    public static List<ItemStack> carried(io.github.izakyl.folkways.plugins.person.ResidentEntity body) {
        List<ItemStack> stacks = new ArrayList<>();
        if (body.hasWeaponSession()) { stacks.add(body.getMainHandItem()); }
        stacks.addAll(body.pack().contents());
        return List.copyOf(stacks);
    }
    public static Component label(Selection selection) {
        return selection.provider().map(LoadoutProvider::label)
            .orElseGet(() -> Component.translatable("folkways.combat.loadout.unavailable"));
    }
    /** Version one stored Java enum names. Preserve unknown extension IDs so absent mods lose no settings. */
    public static Selection read(CompoundTag row) {
        String value = row.getString("loadout");
        ResourceLocation id = switch (value) {
            case "MELEE" -> MELEE.id();
            case "BOW" -> BOW.id();
            case "CROSSBOW" -> CROSSBOW.id();
            default -> ResourceLocation.tryParse(value);
        };
        return id == null ? null : new Selection(id, row.getCompound("options"));
    }
    private record Ordinary(String name, ItemSpec weapon, boolean ranged) implements LoadoutProvider {
        @Override public Component label() { return Component.translatable("folkways.combat.loadout." + name); }
        @Override public ItemSpec weapon(CompoundTag options) { return weapon; }
        @Override public List<Amount> ammunition(CompoundTag options) {
            return ranged ? List.of(new Amount(ItemSpec.of(ItemTags.ARROWS), PatrolSupplies.ARROWS)) : List.of();
        }
        @Override public Optional<CompoundTag> infer(ItemStack stack) {
            return ranged && Goods.matches(stack, weapon) ? Optional.of(new CompoundTag()) : Optional.empty();
        }
        @Override public int loaded(ItemStack stack, ItemSpec ammunition) {
            var charged = stack.get(DataComponents.CHARGED_PROJECTILES);
            return charged == null ? 0 : charged.getItems().stream().filter(item -> Goods.matches(item, ammunition))
                .mapToInt(ItemStack::getCount).sum();
        }
    }
}
