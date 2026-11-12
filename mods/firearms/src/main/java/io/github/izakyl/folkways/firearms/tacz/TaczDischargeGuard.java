package io.github.izakyl.folkways.firearms.tacz;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import io.github.izakyl.folkways.firearms.tacz.mixin.KineticBulletAccessor;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class TaczDischargeGuard {

    // Save compatibility: retain the historical projectile NBT key (see SAVE_COMPATIBILITY.md).
    private static final String COLONY = "folkways_tacz_colony";
    private static final Map<ResidentEntity, TaczWeaponAttack> ACTIVE = new IdentityHashMap<>();

    private TaczDischargeGuard() {
    }

    static void claim(ResidentEntity body, TaczWeaponAttack attack) {
        ACTIVE.put(body, attack);
    }

    static void release(ResidentEntity body, TaczWeaponAttack attack) {
        ACTIVE.remove(body, attack);
    }

    /** Capture the attack object as a generation, before the first synchronous burst discharge. */
    public static BooleanSupplier capture(LivingEntity shooter, ItemStack gun, BooleanSupplier task) {
        if (!(shooter instanceof ResidentEntity body)) {
            return task;
        }
        TaczWeaponAttack generation = ACTIVE.get(body);
        LivingEntity intent = generation == null ? null : generation.target();
        return () ->
            generation != null
                && ACTIVE.get(body) == generation
                && generation.discharge(intent)
                && task.getAsBoolean();
    }

    static boolean clearLine(ResidentEntity body, LivingEntity target) {
        Vec3 from = body.getEyePosition();
        Vec3 to = target.getBoundingBox().getCenter();
        for (LivingEntity other :
            body.level()
            .getEntitiesOfClass(
            LivingEntity.class,
            new AABB(from, to).inflate(1),
            entity -> entity != body && entity != target && entity.isAlive())) {
            if ((other.isAlliedTo(body)
                || other instanceof Player
                || Bodies.of(other).flatMap(value -> value.colonyId()).isPresent()
                || body.colonyId().filter(id -> LivingContent.colonyPet(other, id)).isPresent())
                && (other.getBoundingBox().inflate(0.3).contains(from)
                || other.getBoundingBox().inflate(0.3).clip(from, to).isPresent())) {
                return false;
            }
        }
        return true;
    }

    static void firing(GunFireEvent event) {
        if (event.getLogicalSide().isClient() || !(event.getShooter() instanceof ResidentEntity body)) {
            return;
        }
        TaczWeaponAttack attack = ACTIVE.get(body);
        if (attack == null || !attack.discharge(attack.target())) {
            event.setCanceled(true);
        }
    }

    static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()
            && event.getEntity() instanceof EntityKineticBullet bullet
            && bullet.getOwner() instanceof ResidentEntity body) {
            KineticBulletAccessor payload = (KineticBulletAccessor) bullet;
            if (payload.folkways$explosive()
                || payload.folkways$ignitesEntities()
                || payload.folkways$ignitesBlocks()) {
                // Another fire listener can change properties after our discharge check. Reject its
                // final payload.
                event.setCanceled(true);
                bullet.discard();
                return;
            }
            body.colonyId().ifPresent(id -> bullet.getPersistentData().putUUID(COLONY, id));
        }
    }

    static boolean protectedAlly(Entity bullet, Entity victim) {
        if (!TaczConfig.protectAllies() || victim == null || !bullet.getPersistentData().hasUUID(COLONY)) {
            return false;
        }
        UUID colony = bullet.getPersistentData().getUUID(COLONY);
        return Bodies.of(victim).flatMap(body -> body.colonyId()).filter(colony::equals).isPresent()
            || LivingContent.colonyPet(victim, colony);
    }

    static void hitBlock(AmmoHitBlockEvent event) {
        if (event.getAmmo().getOwner() instanceof ResidentEntity
            || event.getAmmo().getPersistentData().hasUUID(COLONY)) {
            // Stray colony rounds must not break glass or activate blocks outside the work runner.
            event.setCanceled(true);
            event.getAmmo().discard();
        }
    }

    static void hurt(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide().isServer() && protectedAlly(event.getBullet(), event.getHurtEntity())) {
            event.setCanceled(true);
        }
    }
}
