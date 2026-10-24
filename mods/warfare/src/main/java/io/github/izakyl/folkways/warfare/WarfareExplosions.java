package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class WarfareExplosions {
    private static final String RESIDENT_SHOT = "folkways:resident_shot";

    private WarfareExplosions() { }

    static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ProjectileEntity projectile
                && projectile.getOwner() instanceof ResidentEntity body) {
            projectile.getPersistentData().putUUID(RESIDENT_SHOT, body.getUUID());
        }
    }

    public static boolean residentShot(CustomExplosion explosion) {
        Entity direct = explosion.getDirectSourceEntity();
        return explosion.getDamageSource().getEntity() instanceof ResidentEntity
            || direct != null && direct.getPersistentData().hasUUID(RESIDENT_SHOT)
            || direct instanceof Projectile projectile && projectile.getOwner() instanceof ResidentEntity;
    }

    static boolean mayFire(Level level, ResidentEntity body) {
        return EventHooks.canEntityGrief(level, body);
    }

    public static boolean mayDamageBlocks(Level level, CustomExplosion explosion) {
        return !residentShot(explosion) || explosion.getDamageSource().getEntity() instanceof ResidentEntity body
            && EventHooks.canEntityGrief(level, body);
    }

    public static boolean permits(Level level, CustomExplosion explosion) {
        if (!residentShot(explosion)) { return true; }
        Entity attacker = explosion.getDamageSource().getEntity();
        if (!(level instanceof ServerLevel server) || !(attacker instanceof ResidentEntity body)) { return false; }
        if (explosion.interactsWithBlocks() && !EventHooks.canEntityGrief(level, body)) { return false; }
        double radius = explosion.getRadius();
        if (!Double.isFinite(radius) || radius < 0) { return false; }
        if (!WarfareSafety.impact(server, body, new Vec3(explosion.getX(), explosion.getY(), explosion.getZ()),
                radius * 2, explosion.interactsWithBlocks() ? radius * .6 : 0)) { return false; }
        // SBW posts Start after destroying blocks. Post it before either blocks or entities are touched.
        return !EventHooks.onExplosionStart(level, explosion);
    }

    public static Collection<BlockPos> blocks(Level level, CustomExplosion explosion, Collection<BlockPos> blocks) {
        if (!residentShot(explosion)) { return blocks; }
        if (!mayDamageBlocks(level, explosion)) { return List.of(); }
        // Native tiers destroy their block lists before SBW's entity-damage Detonate event.
        var affected = explosion.getToBlow();
        affected.clear(); affected.addAll(blocks);
        EventHooks.onExplosionDetonate(level, explosion, new ArrayList<>(), explosion.getRadius() * 2);
        return blocks.stream().filter(affected::contains).toList();
    }
}
