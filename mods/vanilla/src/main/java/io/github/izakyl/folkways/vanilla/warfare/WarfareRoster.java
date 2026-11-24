package io.github.izakyl.folkways.vanilla.warfare;

import io.github.izakyl.folkways.core.shell.warfare.WarfareWeapon;
import java.util.List;

public final class WarfareRoster {

    public static final List<WarfareWeapon> ALL = List.of(
        new WarfareWeapon("glock_17", "handgun_ammo", 24),
        new WarfareWeapon("mp_5", "handgun_ammo", 32),
        new WarfareWeapon("ak_47", "rifle_ammo", 48),
        new WarfareWeapon("m_4", "rifle_ammo", 48),
        new WarfareWeapon("m_870", "shotgun_ammo", 20),
        new WarfareWeapon("svd", "sniper_ammo", 64));

    private WarfareRoster() {
    }
}
