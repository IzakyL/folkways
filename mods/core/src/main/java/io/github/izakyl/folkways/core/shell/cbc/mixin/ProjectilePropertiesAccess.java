package io.github.izakyl.folkways.core.shell.cbc.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

@Mixin(value = AbstractCannonProjectile.class, remap = false)
public interface ProjectilePropertiesAccess {

    @Invoker("getBallisticProperties")
    BallisticPropertiesComponent folkwaysBallistics();
}
