package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProvider;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class TaczLoadouts implements LoadoutProvider {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "tacz");

    @Override
    public Component label() {
        return Component.translatable("folkways.combat.loadout.tacz");
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return TaczGuns.supported(stack);
    }

    @Override
    public boolean ranged(ItemStack stack) {
        return true;
    }

    @Override
    public double range(ItemStack stack) {
        return TaczGuns.range(stack);
    }

    @Override
    public Optional<ItemSpec> ammunition(ItemStack stack) {
        return accepts(stack)
            ? TaczGuns.index(((IGun) stack.getItem()).getGunId(stack))
            .map(index -> TaczGuns.ammunition(index.getGunData().getAmmoId()))
            : Optional.empty();
    }

    @Override
    public int magazineSize(ItemStack stack) {
        return TaczGuns.index(((IGun) stack.getItem()).getGunId(stack))
            .map(index -> index.getGunData().getAmmoAmount())
            .orElse(1);
    }

    @Override
    public int roundsLoaded(ItemStack stack, ItemSpec ammo) {
        return ammunition(stack)
            .filter(spec -> Goods.matches(Goods.display(spec), ammo))
            .map(spec -> TaczGuns.loaded(stack))
            .orElse(0);
    }

    @Override
    public int cycleTicks(ItemStack stack) {
        return TaczGuns.cycleTicks(stack);
    }

    @Override
    public int suggestedAmount(ItemStack stack) {
        return Math.clamp((long) magazineSize(stack) * 3, 1, 4096);
    }

    @Override
    public List<ItemStack> weaponChoices() {
        return TimelessAPI.getAllCommonGunIndex().stream()
            .map(entry -> entry.getKey())
            .filter(id -> TaczGuns.index(id).isPresent())
            .sorted()
            .map(id -> Goods.display(TaczGuns.weapon(id)))
            .filter(this::accepts)
            .toList();
    }

    @Override
    public ItemSpec profileWeapon(ItemStack stack) {
        return TaczGuns.weapon(((IGun) stack.getItem()).getGunId(stack));
    }
}
