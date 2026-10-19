package io.github.izakyl.folkways.core.shell.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mob.class)
public interface MobAccessor {

    @Accessor("navigation")
    void folkways$setNavigation(PathNavigation navigation);

    @Accessor("moveControl")
    void folkways$setMoveControl(MoveControl moveControl);
}
