package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.resident.api.Body;

/** A body that transfers a real pack item to its main hand and owns the active attack. */
public interface WeaponHands extends Body {
    boolean weaponDrawn();

    boolean hasActiveAttack();

    boolean isActiveAttack(WeaponAttack attack);

    boolean drawWeapon(int packSlot);

    void holsterWeapon();

    void beginAttack(WeaponAttack attack);

    void endAttack(WeaponAttack attack);
}
