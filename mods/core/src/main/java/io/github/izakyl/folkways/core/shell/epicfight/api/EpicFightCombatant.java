package io.github.izakyl.folkways.core.shell.epicfight.api;

import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Mob;
import yesman.epicfight.api.animation.Animator;

public interface EpicFightCombatant {
    boolean uninitialized();

    boolean blocksLabor();

    boolean isStunned();

    <A extends Animator> A getAnimator();

    boolean mayEngage(Worker worker);

    boolean hostile(Mob target);

    BooleanSupplier engagementLease();

    boolean beginStrike(EpicFightStrike attack);

    boolean ownsStrike(EpicFightStrike attack);

    void endStrike(EpicFightStrike attack, boolean cancelAnimation);
}
