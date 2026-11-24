package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.LoadoutProvider;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.WeaponHands;
import io.github.izakyl.folkways.core.combat.api.Weapons;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class WarfareWeapons {

    public static final ResourceLocation LOADOUT = ResourceLocation.fromNamespaceAndPath("folkways_core", "warfare/firearm");

    private static List<WarfareWeapon> roster = List.of();

    private static final class Firearm implements LoadoutProvider {

        private WarfareWeapon adapter(ItemStack stack) {
            return roster.stream().filter(weapon -> weapon.accepts(stack)).findFirst().orElseThrow();
        }

        public boolean accepts(ItemStack stack) {
            return roster.stream().anyMatch(weapon -> weapon.accepts(stack));
        }

        public boolean ranged(ItemStack stack) {
            return true;
        }

        public double range(ItemStack stack) {
            return adapter(stack).range();
        }

        public Optional<ItemSpec> ammunition(ItemStack stack) {
            return Optional.of(adapter(stack).ammunition());
        }

        public int magazineSize(ItemStack stack) {
            return GunData.from(stack).get(GunProp.MAGAZINE);
        }

        public int roundsLoaded(ItemStack stack, ItemSpec ammunition) {
            return Goods.matches(Goods.display(adapter(stack).ammunition()), ammunition)
                ? GunData.from(stack).ammo.get()
                : 0;
        }

        public int cycleTicks(ItemStack stack) {
            GunData data = GunData.from(stack);
            return Math.max(
                data.get(GunProp.BOLT_ACTION_TIME) + 1,
                Math.max(3, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
        }

    }

    private WarfareWeapons() {
    }

    public static void register(List<WarfareWeapon> weapons) {
        roster = List.copyOf(weapons);
        for (WarfareWeapon weapon : roster) {
            Weapons.register(weapon.id(), weapon);
        }
        Loadouts.register(LOADOUT, new Firearm());
    }

    static void ticked(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof WeaponHands body) || body.mob().level().isClientSide()) {
            return;
        }
        boolean changed = false;
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            changed |= tick(body, body.pack().getItem(slot));
        }
        if (changed) {
            body.pack().setChanged();
        }
        if (body.weaponDrawn() && tick(body, body.mob().getMainHandItem())
            && body.mob().level() instanceof ServerLevel level) {
            body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
                .ifPresent(colony -> colony.inventoryFeedback().changedResident(body.id()));
        }
    }

    private static boolean tick(WeaponHands body, ItemStack stack) {
        if (roster.stream().noneMatch(weapon -> weapon.accepts(stack))) {
            return false;
        }
        ItemStack before = stack.copy();
        GunData data = GunData.from(stack);
        if (data.shouldStartBolt()) {
            data.startBolt();
        }
        data.tick(body.mob(), true);
        return !ItemStack.matches(before, stack);
    }

    static ResourceLocation nativeId(String path) {
        return ResourceLocation.fromNamespaceAndPath("superbwarfare", path);
    }
}
