package io.github.izakyl.folkways.core.shell.epicfight.api;

import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;

/** A strike driven by the patch's native animation and collision callbacks. */
public interface EpicFightStrike extends WeaponAttack {
    void beforeAnimationTick();

    LivingEntity target();

    boolean authorized();

    boolean playing();

    boolean permits(LivingEntity victim);

    boolean admitHit(EpicFightDamageSource source, LivingEntity victim);

    void spentHit(LivingEntity victim);
}
