package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;

/** Short runtime observations only; healing still belongs to real hunger and world gamerules. */
public final class CombatReadiness {
    private static final Map<Mob, Long> unsafeAt = new WeakHashMap<>();
    private CombatReadiness() { }

    public static Optional<Creeper> explosiveNear(Mob body) {
        return body.level().getEntitiesOfClass(Creeper.class, body.getBoundingBox().inflate(10),
            foe -> Threats.hostile(foe, body) && body.hasLineOfSight(foe)
                && body.distanceToSqr(foe) <= Math.pow(foe.isPowered() ? 10 : foe.isIgnited() || foe.getSwellDir() > 0 ? 8 : 6, 2))
            .stream().min(java.util.Comparator.comparingDouble(body::distanceToSqr));
    }
    public static void fighting(Mob body) { unsafeAt.put(body, body.level().getGameTime()); }
    public static boolean quiet(Mob body) {
        long now = body.level().getGameTime();
        boolean unsafe = body.hurtTime > 0 || body.isOnFire()
            || body instanceof ResidentEntity resident && resident.hasWeaponSession()
            || !body.level().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(12),
                foe -> Threats.hostile(foe, body) && body.hasLineOfSight(foe)).isEmpty();
        if (unsafe) { fighting(body); return false; }
        return now - unsafeAt.computeIfAbsent(body, ignored -> now) >= 100;
    }
}
