package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProvider;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class WarfareWeapons {
    public static final List<WarfareWeapon> ALL = List.of(
        new WarfareWeapon("glock_17", "handgun_ammo", 24), new WarfareWeapon("mp_5", "handgun_ammo", 32),
        new WarfareWeapon("ak_47", "rifle_ammo", 48), new WarfareWeapon("m_4", "rifle_ammo", 48),
        new WarfareWeapon("m_870", "shotgun_ammo", 20), new WarfareWeapon("svd", "sniper_ammo", 64));
    public static final ResourceLocation LOADOUT = ResourceLocation.fromNamespaceAndPath("folkways", "warfare/firearm");
    public static final LoadoutRegistry.Selection FIREARM = new LoadoutRegistry.Selection(LOADOUT, options(ALL.getFirst()));

    private static CompoundTag options(WarfareWeapon weapon) {
        CompoundTag options = new CompoundTag();
        options.putString("gun", weapon.id().toString());
        return options;
    }

    private static final class Firearm implements LoadoutProvider {
        private Optional<WarfareWeapon> adapter(CompoundTag options) {
            return ALL.stream().filter(weapon -> weapon.id().toString().equals(options.getString("gun"))).findFirst();
        }
        public Component label() { return Component.translatable("folkways.combat.loadout.warfare.firearm"); }
        public boolean valid(CompoundTag options) { return adapter(options).isPresent(); }
        public ItemSpec weapon(CompoundTag options) { return adapter(options).orElseThrow().weapon(); }
        public List<Amount> ammunition(CompoundTag options) { return List.of(new Amount(adapter(options).orElseThrow().ammunition(), 16)); }
        public Optional<CompoundTag> infer(ItemStack stack) { return ALL.stream().filter(weapon -> weapon.accepts(stack)).findFirst().map(WarfareWeapons::options); }
        public List<Choice> choices() {
            return ALL.stream().map(weapon -> new Choice(options(weapon), label().copy().append(": ")
                .append(BuiltInRegistries.ITEM.get(weapon.nativeId()).getDescription()))).toList();
        }
    }

    private WarfareWeapons() { }
    static void declare(Declaring declaring) {
        for (WarfareWeapon weapon : ALL) {
            Weapons.register(weapon.id(), weapon);
            CoreSettings.defaultTool(weapon.weapon());
        }
        LoadoutRegistry.register(LOADOUT, new Firearm());
    }
    static void ticked(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ResidentEntity body) || body.level().isClientSide()) { return; }
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) { tick(body, body.pack().getItem(slot)); }
        if (body.hasWeaponSession()) { tick(body, body.getMainHandItem()); }
    }
    private static void tick(ResidentEntity body, ItemStack stack) {
        if (ALL.stream().noneMatch(weapon -> weapon.accepts(stack))) { return; }
        GunData data = GunData.from(stack);
        if (data.shouldStartBolt()) { data.startBolt(); }
        data.tick(body, true);
    }
    static ResourceLocation nativeId(String path) { return ResourceLocation.fromNamespaceAndPath("superbwarfare", path); }
}
