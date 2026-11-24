package io.github.izakyl.folkways.core.shell.cbc.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

@Mixin(value = CannonMountBlockEntity.class, remap = false)
public interface CannonMountAccess {

    @Accessor("cannonYaw")
    float folkwaysYaw();

    @Accessor("cannonPitch")
    float folkwaysPitch();
}
