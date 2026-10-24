package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Gives tagged native monster AI an additional eligible target without replacing their
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
        if (!(mob instanceof Enemy) || mob instanceof NeutralMob) {
            return;
        }
        // An entity can re-enter the level; neither a duplicate target goal nor a stale
        // registry of entity UUIDs may accumulate. The goal itself lives with the entity.
        if (mob.targetSelector.getAvailableGoals().stream()
            .anyMatch(goal -> goal.getGoal() instanceof ResidentTargetGoal)) {
            return;
        }
        // Keep every existing native target ahead, including modded Enemy subclasses with later priorities.
        int last = mob.targetSelector.getAvailableGoals().stream().mapToInt(goal -> goal.getPriority()).max().orElse(0);
        int priority = Math.max(6, Math.min(Integer.MAX_VALUE - 1, last) + 1);
        mob.targetSelector.addGoal(priority, new ResidentTargetGoal(mob));
    }

    static boolean eligible(LivingEntity target) {
        return target.isAlive() && !target.isRemoved()
            && Bodies.of(target).flatMap(body -> body.colonyId()).isPresent();
    }

    // Strategic extensions may narrow resident acquisition without replacing native attack AI.
    public static void restrictResidents(Mob mob, java.util.function.Predicate<LivingEntity> permits) {
        install(mob);
        mob.targetSelector.getAvailableGoals().stream().map(goal -> goal.getGoal())
            .filter(ResidentTargetGoal.class::isInstance).map(ResidentTargetGoal.class::cast)
            .forEach(goal -> goal.permits = permits);
    }

    private static final class ResidentTargetGoal extends NearestAttackableTargetGoal<Mob> {

        private java.util.function.Predicate<LivingEntity> permits = target -> true;

        private ResidentTargetGoal(Mob mob) {
            super(mob, Mob.class, 10, true, false, NativeHostility::eligible);
            targetConditions.selector(target -> eligible(target) && permits.test(target));
        }

        @Override
        public boolean canUse() {
            // Tag lookup stays in the AI tick, including after /reload. Untagged monsters do not scan.
            // SpiderAttackGoal retains vanilla's random disengagement after daylight returns.
            return targetsResidents(mob) && (!(mob instanceof Spider) || mob.getLightLevelDependentMagicValue() < .5F)
                && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            // TargetGoal only checks combat eligibility, not our enrollment predicate.
            // Leaving a colony must end this pursuit as well as prevent a new one.
            return targetsResidents(mob) && mob.getTarget() != null && eligible(mob.getTarget()) && permits.test(mob.getTarget()) && super.canContinueToUse();
        }
    }

    public static boolean targetsResidents(Mob mob) {
        return mob instanceof Enemy && !(mob instanceof NeutralMob)
            && Bodies.of(mob).flatMap(body -> body.colonyId()).isEmpty()
            && mob.getType().is(CombatTags.RESIDENT_ENEMIES) && !mob.getType().is(CombatTags.IGNORED);
    }
}
