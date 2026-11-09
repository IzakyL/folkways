package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Conservative ally clearance for a direct shot, repeated at discharge. */
public final class FireSafety {
    private FireSafety() {
    }

    public static boolean clear(Mob shooter, LivingEntity target) {
        return clear(shooter, target, shooter.getEyePosition());
    }

    public static boolean lineOfSight(Mob shooter, LivingEntity target, Vec3 from) {
        return shooter.level().clip(new ClipContext(from, target.getEyePosition(), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, shooter)).getType() == HitResult.Type.MISS;
    }

    public static boolean clear(Mob shooter, LivingEntity target, Vec3 from) {
        Vec3 to = target.getPosition(1).add(0, target.getBbHeight() / 3, 0);
        Optional<UUID> colony = Bodies.of(shooter).flatMap(body -> body.colonyId());
        boolean fromBody = shooter.getBoundingBox().contains(from);
        return shooter.level().getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(1),
            ally -> (ally != shooter || !fromBody) && ally != target && ally.isAlive()
                && (ally == shooter || shooter.isAlliedTo(ally) || ally.isAlliedTo(shooter)
                || colony.isPresent() && Bodies.of(ally).flatMap(body -> body.colonyId()).equals(colony)))
            .stream().noneMatch(ally -> ally.getBoundingBox().inflate(.35).clip(from, to).isPresent());
    }
}
