package io.github.izakyl.folkways.vanilla.epicfight;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.core.shell.epicfight.api.EpicFightCombatant;
import io.github.izakyl.folkways.core.shell.epicfight.api.EpicFightStrike;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.vanilla.combat.Threats;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentWeapons;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.UseAnim;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.ActionAnimation;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.DodgeAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.utils.AttackResult;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.HumanoidMobPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;
import yesman.epicfight.world.damagesource.StunType;
import yesman.epicfight.world.entity.ai.goal.CombatBehaviors;

/** EF owns animation and collision; Folkways continues to own jobs, targets and navigation. */
public final class ResidentEpicFightPatch extends HumanoidMobPatch<ResidentEntity> implements EpicFightCombatant {
    private static final double RUNNING_PACE = 1.15;
    private static final float RUNNING_STRIDE = 0.85F;
    private static volatile Predicate<ResidentEntity> rendererEligibility = resident -> true;
    private EpicFightStrike strike;

    public ResidentEpicFightPatch(ResidentEntity original) {
        super(original, Factions.VILLAGER);
    }

    public static ResidentEpicFightPatch of(ResidentEntity resident) {
        return EpicFightCapabilities.getEntityPatch(resident, ResidentEpicFightPatch.class);
    }

    /** Installed from the client without linking client-only render types into the common patch. */
    public static void renderingWhen(Predicate<ResidentEntity> eligibility) {
        rendererEligibility = Objects.requireNonNull(eligibility);
    }

    @Override
    protected void initAI() { }

    @Override
    public void setAIAsInfantry(boolean holdingRangedWeapon) { }

    @Override
    public void setAIAsMounted(Entity vehicle) { }

    @Override
    public void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, Animations.BIPED_IDLE);
        animator.addLivingAnimation(LivingMotions.WALK, Animations.BIPED_WALK);
        animator.addLivingAnimation(LivingMotions.RUN, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.CHASE, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.SIT, Animations.BIPED_SIT);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
        animator.addLivingAnimation(LivingMotions.DEATH, Animations.BIPED_DEATH);
        animator.addLivingAnimation(LivingMotions.JUMP, Animations.BIPED_JUMP);
        animator.addLivingAnimation(LivingMotions.SLEEP, Animations.BIPED_SLEEPING);
        animator.addLivingAnimation(LivingMotions.AIM, Animations.BIPED_BOW_AIM);
        animator.addLivingAnimation(LivingMotions.SHOT, Animations.BIPED_BOW_SHOT);
        animator.addLivingAnimation(LivingMotions.DRINK, Animations.BIPED_DRINK);
        animator.addLivingAnimation(LivingMotions.EAT, Animations.BIPED_EAT);
        animator.addLivingAnimation(LivingMotions.BLOCK_SHIELD, Animations.BIPED_BLOCK);
    }

    @Override
    public void updateMotion(boolean considerInaction) {
        commonMobUpdateMotion(considerInaction);
        if (original.isAlive() && (!considerInaction || !getEntityState().inaction())) {
            if (original.isSleeping()) {
                currentLivingMotion = LivingMotions.SLEEP;
            } else if (currentLivingMotion == LivingMotions.WALK && runningMotion()) {
                currentLivingMotion = LivingMotions.RUN;
            }
            currentCompositeMotion = currentLivingMotion;
            if (original.isUsingItem()) {
                UseAnim use = original.getUseItem().getUseAnimation();
                if (use == UseAnim.EAT) { currentCompositeMotion = LivingMotions.EAT; }
                if (use == UseAnim.DRINK) { currentCompositeMotion = LivingMotions.DRINK; }
                if (use == UseAnim.BLOCK) { currentCompositeMotion = LivingMotions.BLOCK_SHIELD; }
                if (use == UseAnim.BOW || use == UseAnim.CROSSBOW) { currentCompositeMotion = LivingMotions.AIM; }
            }
        }
    }

    private boolean runningMotion() {
        if (original.isSprinting()) { return true; }
        if (!isLogicalClient()) {
            // Folkways expresses retreat/flee pace through navigation, without setting the sprint flag.
            return !original.getNavigation().isDone()
                && original.getMoveControl().getSpeedModifier() >= RUNNING_PACE;
        }
        // Navigation is server-only. Remote stride follows interpolated displacement and is smoothed by vanilla.
        return original.walkAnimation.speed() > RUNNING_STRIDE;
    }

    @Override
    public AnimationAccessor<? extends StaticAnimation> getHitAnimation(StunType type) {
        return switch (type) {
            case NONE -> null;
            case SHORT -> Animations.BIPED_HIT_SHORT;
            case KNOCKDOWN, FALL -> Animations.BIPED_KNOCKDOWN;
            case NEUTRALIZE -> Animations.BIPED_COMMON_NEUTRALIZED;
            default -> Animations.BIPED_HIT_LONG;
        };
    }

    @Override
    public void preTick() {
        if (!isLogicalClient() && strike != null) { strike.beforeAnimationTick(); }
        super.preTick();
    }

    @Override
    public boolean shouldMoveOnCurrentSide(ActionAnimation action) {
        // No work node grants dodge/root-motion authority in the upstream combat design.
        if (action instanceof DodgeAnimation) { return false; }
        if (action instanceof AttackAnimation) {
            return !isLogicalClient() && strike != null && strike.authorized()
                && super.shouldMoveOnCurrentSide(action);
        }
        return super.shouldMoveOnCurrentSide(action);
    }

    @Override
    public LivingEntity getTarget() {
        // Also prevents HumanoidMobPatch's mounted chase helper from driving a resident's vehicle.
        return strike != null && !original.isPassenger() && strike.authorized() ? strike.target() : null;
    }

    /** A strike must keep its owning BodyRunner ticking; only external stun pauses labor. */
    @Override
    public boolean mayEngage(Worker worker) {
        return ResidentWeapons.permitted(worker);
    }

    @Override
    public boolean hostile(Mob target) {
        return Threats.hostile(target, original);
    }

    @Override
    public BooleanSupplier engagementLease() {
        var colony = original.colonyId();
        if (colony.isEmpty() || !(original.level() instanceof ServerLevel level)) {
            return () -> true;
        }
        var id = colony.get();
        var command = Colonies.of(level.getServer(), id).map(DefenseControl::of)
            .flatMap(commands -> commands.current(original.id())).orElse(null);
        // No order is a lease identity too: self-defense cannot survive a new MOVE/RETREAT.
        return () -> Colonies.of(level.getServer(), id)
            .map(live -> DefenseControl.of(live).current(original.id()).orElse(null) == command)
            .orElse(false);
    }

    @Override
    public boolean blocksLabor() {
        return animator != null && isStunned();
    }

    public boolean actionOwnsPose() {
        return animator != null && (getEntityState().inaction() || getEntityState().attacking()
            || getEntityState().hurt() || getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);
    }

    @Override
    public boolean overrideRender() {
        return !original.level().isClientSide() || rendererEligibility.test(original);
    }

    @Override
    public boolean beginStrike(EpicFightStrike attack) {
        if (strike != null || blocksLabor()) { return false; }
        strike = attack;
        CombatBehaviors<HumanoidMobPatch<?>> behaviors = getHoldingItemWeaponMotionBuilder().build(this);
        CombatBehaviors.Behavior<HumanoidMobPatch<?>> selected = behaviors.selectRandomBehaviorSeries();
        if (selected != null) {
            selected.execute(this);
        } else {
            // The work node has already admitted range/target. Its first basic move remains usable
            // when a randomized AI series predicate does not happen to select an attack this tick.
            behaviors.execute(0);
        }
        return true;
    }

    @Override
    public boolean ownsStrike(EpicFightStrike attack) { return strike == attack; }

    @Override
    public void endStrike(EpicFightStrike attack, boolean cancelAnimation) {
        if (strike != attack) { return; }
        boolean playing = attack.playing() || actionOwnsPose();
        strike = null;
        if (cancelAnimation && playing && original.isAlive()) {
            // Replacing the base action clears a pending link as well as its attack. StopPlaying alone does not.
            playAnimationSynchronized(Animations.BIPED_IDLE, 0);
        }
    }

    @Override
    public boolean applyStun(StunType type, float duration) {
        if (type != StunType.NONE && strike != null) { strike.stop(); }
        return super.applyStun(type, duration);
    }

    @Override
    public boolean isTargetInvulnerable(Entity target) {
        return super.isTargetInvulnerable(target) || !(target instanceof LivingEntity living)
            || strike == null || !strike.permits(living);
    }

    @Override
    public AttackResult attack(EpicFightDamageSource source, Entity target, InteractionHand hand) {
        EpicFightStrike active = strike;
        if (hand != InteractionHand.MAIN_HAND || !(target instanceof LivingEntity living)
                || active == null || !active.admitHit(source, living)) {
            return AttackResult.missed(0);
        }
        AttackResult result = super.attack(source, target, hand);
        if (result.resultType.dealtDamage()) { active.spentHit(living); }
        return result;
    }
}
