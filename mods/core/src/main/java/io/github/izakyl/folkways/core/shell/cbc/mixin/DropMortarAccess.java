package io.github.izakyl.folkways.core.shell.cbc.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;

@Mixin(value = MountedBigCannonContraption.class, remap = false)
public interface DropMortarAccess {

    @Accessor("cachedMortarRound")
    ItemStack folkwaysMortarRound();

    @Accessor("mortarDelay")
    int folkwaysMortarDelay();
}
