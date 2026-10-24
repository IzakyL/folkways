package io.github.izakyl.folkways.plugins.person.mixin;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** BowItem.releaseUsing only accepts players; expose its shared native mob-capable primitives. */
@Mixin(ProjectileWeaponItem.class)
public interface ProjectileWeaponInvoker {
    @Invoker("draw")
    static List<ItemStack> folkways$draw(ItemStack weapon, ItemStack ammunition, LivingEntity shooter) {
        throw new AssertionError("mixin not applied");
    }

    @Invoker("shoot")
    void folkways$shoot(ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon,
        List<ItemStack> ammunition, float speed, float inaccuracy, boolean critical, LivingEntity target);
}
