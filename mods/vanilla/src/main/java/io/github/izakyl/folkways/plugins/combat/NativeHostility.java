package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Gives native zombie and skeleton AI an additional eligible target without replacing their
 * movement, attack goals, or existing targets. War is not required for this world behaviour.
 */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.GAME)
public final class NativeHostility {

    private NativeHostility() {
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        install(mob);
    }

    static void install(Mob mob) {
        if (!(mob instanceof Zombie || mob instanceof AbstractSkeleton)) {
            return;
        }
        // An entity can re-enter the level; neither a duplicate target goal nor a stale
        // registry of entity UUIDs may accumulate. The goal itself lives with the entity.
        if (mob.targetSelector.getAvailableGoals().stream()
            .anyMatch(goal -> goal.getGoal() instanceof ResidentTargetGoal)) {
            return;
        }
        mob.targetSelector.addGoal(3, new ResidentTargetGoal(mob));
    }

    static boolean eligible(LivingEntity target) {
        return target.isAlive() && !target.isRemoved()
            && Bodies.of(target).flatMap(body -> body.colonyId()).isPresent();
    }

    private static final class ResidentTargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

        private ResidentTargetGoal(Mob mob) {
            super(mob, LivingEntity.class, 10, true, false, NativeHostility::eligible);
        }
    }
}
