package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

final class TaczGuns {
    private static final Set<String> SCRIPTS = Set.of("xmag_reload_logic", "kar98_gun_logic", "m870_gun_logic",
        "db_short_gun_logic", "hk_mk23_logic", "m1014_gun_logic", "spas_12_gun_logic");
    private TaczGuns() { }
    static Optional<CommonGunIndex> index(ResourceLocation id) {
        return TaczConfig.allowed(id) ? TimelessAPI.getCommonGunIndex(id).filter(index -> {
            var data = index.getGunData();
            var bullet = index.getBulletData();
            var script = data.getScript();
            return data.getAmmoAmount() > 0 && !data.getReloadData().isInfinite()
                && data.getReloadData().getType() != FeedType.FUEL
                && data.getReloadData().getType() != FeedType.INVENTORY
                && data.getAmmoId() != null && TimelessAPI.getCommonAmmoIndex(data.getAmmoId()).isPresent()
                && (bullet.getExplosionData() == null || !bullet.getExplosionData().isExplode())
                && !bullet.getIgnite().isIgniteBlock() && !bullet.getIgnite().isIgniteEntity()
                && data.getFireModeSet().stream().noneMatch(mode -> mode == FireMode.UNKNOWN || data.getChargeData(mode) != null)
                && (script == null || script.getNamespace().equals("tacz") && SCRIPTS.contains(script.getPath()));
        }) : Optional.empty();
    }
    static boolean supported(ItemStack stack) {
        return stack.is(ModItems.MODERN_KINETIC_GUN.get()) && stack.getItem() instanceof IGun gun
            && !gun.useDummyAmmo(stack) && !gun.useInventoryAmmo(stack) && index(gun.getGunId(stack)).isPresent();
    }
    static int loaded(ItemStack stack) {
        if (!(stack.getItem() instanceof IGun gun)) { return 0; }
        return Math.max(0, gun.getCurrentAmmoCount(stack)) + (gun.hasBulletInBarrel(stack) && TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
            .filter(index -> index.getGunData().getBolt() != Bolt.OPEN_BOLT).isPresent() ? 1 : 0);
    }
    static ItemSpec identity(Item item, String key, ResourceLocation id) {
        CompoundTag data = new CompoundTag(); data.putString(key, id.toString());
        return ItemSpec.of(item).withComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(data)).build());
    }
    static ItemSpec weapon(ResourceLocation id) { return identity(ModItems.MODERN_KINETIC_GUN.get(), "GunId", id); }
    static ItemSpec ammunition(ResourceLocation id) { return identity(ModItems.AMMO.get(), "AmmoId", id); }
}
