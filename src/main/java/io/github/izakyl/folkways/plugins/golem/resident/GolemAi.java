package io.github.izakyl.folkways.plugins.golem.resident;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.entity.mode.GolemModes;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

final class GolemAi {

    private static final Method REGISTER_GOALS = registerGoals();

    private GolemAi() {
    }

    static void takeOver(AbstractGolemEntity<?, ?> golem) {
        golem.goalSelector.removeAllGoals(goal -> true);
        golem.targetSelector.removeAllGoals(goal -> true);
        golem.setMode(GolemModes.FREE_WANDER.getID(), BlockPos.ZERO);
        hold(golem);
    }

    static void release(AbstractGolemEntity<?, ?> golem) {
        golem.goalSelector.removeAllGoals(goal -> true);
        golem.targetSelector.removeAllGoals(goal -> true);
        try {
            REGISTER_GOALS.invoke(golem);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("could not give a golem its own goals back", failed);
        }
    }

    private static Method registerGoals() {
        try {
            Method found = Mob.class.getDeclaredMethod("registerGoals");
            found.setAccessible(true);
            return found;
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("mobs no longer register their goals by name", missing);
        }
    }

    static void hold(AbstractGolemEntity<?, ?> golem) {
        if (golem.getTarget() != null) {
            golem.setTarget(null);
        }
        if (golem.getPersistentAngerTarget() != null) {
            golem.setPersistentAngerTarget(null);
        }
        if (golem.getRemainingPersistentAngerTime() > 0) {
            golem.setRemainingPersistentAngerTime(0);
        }
        if (golem.getMode() != GolemModes.FREE_WANDER) {
            golem.setMode(GolemModes.FREE_WANDER.getID(), BlockPos.ZERO);
        }
    }
}
