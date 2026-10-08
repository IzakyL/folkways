package io.github.izakyl.folkways.core.shell;

import io.github.izakyl.folkways.core.api.resident.MobControls;
import io.github.izakyl.folkways.core.shell.mixin.MobAccessor;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

public final class MobFitting implements MobControls.Fitting {

    private MobFitting() {
    }

    public static void install() {
        MobControls.install(new MobFitting());
    }

    @Override
    public void navigation(Mob body, PathNavigation navigation) {
        ((MobAccessor) body).folkways$setNavigation(navigation);
    }

    @Override
    public void moveControl(Mob body, MoveControl control) {
        ((MobAccessor) body).folkways$setMoveControl(control);
    }
}
