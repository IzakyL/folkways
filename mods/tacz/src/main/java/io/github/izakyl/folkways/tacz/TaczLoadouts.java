package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProvider;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class TaczLoadouts implements LoadoutProvider {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "tacz");
    static LoadoutRegistry.Selection selection(ResourceLocation gun) {
        CompoundTag options = new CompoundTag(); options.putString("gun", gun.toString());
        return new LoadoutRegistry.Selection(ID, options);
    }
    private ResourceLocation gun(CompoundTag options) { return ResourceLocation.parse(options.getString("gun")); }
    @Override public Component label() { return Component.translatable("folkways.combat.loadout.tacz"); }
    @Override public boolean valid(CompoundTag options) {
        ResourceLocation id = ResourceLocation.tryParse(options.getString("gun"));
        return id != null && options.getAllKeys().equals(java.util.Set.of("gun")) && TaczGuns.index(id).isPresent();
    }
    @Override public List<Choice> choices() {
        return TimelessAPI.getAllCommonGunIndex().stream().map(java.util.Map.Entry::getKey)
            .filter(id -> TaczGuns.index(id).isPresent()).sorted().map(id -> {
                ItemStack stack = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
                ((IGun) stack.getItem()).setGunId(stack, id);
                return new Choice(selection(id).options(), Component.translatable("folkways.tacz.gun", stack.getHoverName()));
            }).toList();
    }
    @Override public ItemSpec weapon(CompoundTag options) { return TaczGuns.weapon(gun(options)); }
    @Override public List<Amount> ammunition(CompoundTag options) {
        return List.of(new Amount(TaczGuns.ammunition(TaczGuns.index(gun(options)).orElseThrow().getGunData().getAmmoId()), TaczConfig.rounds()));
    }
    @Override public int lowWater(CompoundTag options, Amount ammunition) { return TaczConfig.lowWater(); }
    @Override public Optional<CompoundTag> infer(ItemStack stack) {
        return TaczGuns.supported(stack) ? Optional.of(selection(((IGun) stack.getItem()).getGunId(stack)).options()) : Optional.empty();
    }
    @Override public int loaded(ItemStack stack, ItemSpec ammunition) {
        if (!TaczGuns.supported(stack)) { return 0; }
        ResourceLocation ammo = TaczGuns.index(((IGun) stack.getItem()).getGunId(stack)).orElseThrow().getGunData().getAmmoId();
        ItemStack round = new ItemStack(ModItems.AMMO.get());
        ((com.tacz.guns.api.item.IAmmo) round.getItem()).setAmmoId(round, ammo);
        return Goods.matches(round, ammunition) ? TaczGuns.loaded(stack) : 0;
    }
    @Override public Component detail(ResidentEntity body, CompoundTag options) {
        if (!valid(options)) {
            ResourceLocation id = ResourceLocation.tryParse(options.getString("gun"));
            return Component.translatable(id == null || TimelessAPI.getCommonGunIndex(id).isEmpty()
                ? "folkways.tacz.missing_pack" : "folkways.tacz.unsupported");
        }
        ItemSpec gun = weapon(options);
        Optional<ItemStack> stack = LoadoutRegistry.carried(body).stream().filter(item -> Goods.matches(item, gun)).findFirst();
        if (stack.isEmpty()) { return Component.translatable("folkways.tacz.missing_pack"); }
        if (!TaczGuns.supported(stack.get())) { return Component.translatable("folkways.tacz.unsupported"); }
        long rounds = Goods.countIn(body.pack(), ammunition(options).getFirst().spec());
        Component count = Component.translatable("folkways.tacz.ammo_count", TaczGuns.loaded(stack.get()), rounds);
        return Component.translatable("folkways.tacz.gun", stack.get().getHoverName()).append(" · ").append(Component.translatable("folkways.tacz.ammo")).append(": ").append(count)
            .append(IGunOperator.fromLivingEntity(body).getSynReloadState().getStateType().isReloading()
                ? Component.translatable("folkways.tacz.reloading") : Component.empty());
    }
}
