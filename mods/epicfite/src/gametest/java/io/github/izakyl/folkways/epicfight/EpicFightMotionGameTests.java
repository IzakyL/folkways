package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.combat.CombatReadiness;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.motion.CombatEvasion;
import io.github.izakyl.folkways.plugins.combat.motion.CombatMotions;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.api.animation.types.DodgeAnimation;

@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicFightMotionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightMotionGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void nativeStepMovesTheActualResidentAndPreservesItsExactPackedWeapon(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            Vec3 before = s.resident.position(); ItemStack actual = s.weapon;
            CombatMotions.Session motion = s.dodge(() -> true, s::within).orElseThrow();
            helper.assertTrue(s.patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation,
                "the action is an actual Epic Fight DodgeAnimation");
            helper.assertTrue(s.resident.getNavigation().isDone(), "the step adds no navigation destination");
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.openAssigned(s.worker, actual).isEmpty(), "an attack cannot steal the pose during the native step");
            helper.runAfterDelay(12, () -> {
                try {
                    Vec3 moved = s.resident.position().subtract(before);
                    helper.assertTrue(moved.horizontalDistanceSqr() > .16 && moved.x > .3, "EF root motion physically moves the server resident in the requested direction: " + moved);
                    helper.assertTrue(moved.horizontalDistanceSqr() < 9, "one short action does not turn into unbounded movement");
                    helper.assertTrue(s.resident.getNavigation().isDone(), "native root movement never installs a path or goal");
                    helper.assertFalse(s.resident.hasWeaponSession(), "movement never borrows a weapon");
                    helper.assertTrue(ResidentEquipment.assigned(s.resident) == actual, "the same physical designated item stays in the pack");
                    helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "movement makes no duplicate item");
                    helper.assertValueEqual(actual.getDamageValue(), 7, "a movement animation spends no attack durability");
                    motion.close();
                } finally { s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large")
    public static void aPlayerBoundaryRejectsTheWholeNativeStepBeforeItBegins(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            Vec3 before = s.resident.position();
            helper.assertTrue(s.dodge(() -> true, point -> point.distanceToSqr(before) < .04).isEmpty(), "a native step cannot leave the command's small allowed domain");
            helper.assertFalse(s.patch.hasMotion(), "a rejected step owns no action");
            helper.assertValueEqual(s.resident.position(), before, "preflight rejection moves no real entity");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void nativeDodgeFramesEvadeARealMeleeHitButClosingRestoresOrdinaryDamage(GameTestHelper helper) {
        Scene s = new Scene(helper);
        var attacker = EntityType.ZOMBIE.create(s.level);
        try {
            attacker.setNoAi(true); attacker.moveTo(s.resident.getX() - 2, s.resident.getY(), s.resident.getZ()); s.level.addFreshEntity(attacker);
            CombatMotions.Session motion = s.dodge(() -> true, s::within).orElseThrow();
            helper.runAfterDelay(2, () -> {
                try {
                    float health = s.resident.getHealth();
                    var source = s.level.damageSources().mobAttack(attacker);
                    helper.assertFalse(s.resident.hurt(source, 4), "the native EF dodge state rejects a real dodgeable melee hit");
                    helper.assertValueEqual(s.resident.getHealth(), health, "the actual native dodge frame prevents the health loss");
                    motion.close();
                    helper.assertTrue(s.resident.hurt(source, 4), "closing the motion removes native evasion instead of leaving permanent invulnerability");
                    helper.assertTrue(s.resident.getHealth() < health, "ordinary real damage works again after cancellation");
                } finally { attacker.discard(); s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { attacker.discard(); s.close(); throw failure; }
    }

    @GameTest(template = "empty_large")
    public static void nativeStepRefusesASolidWallInsteadOfClippingOrChoosingTheWrongDirection(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            BlockPos at = s.resident.blockPosition();
            for (int z = -3; z <= 3; z++) for (int y = 0; y < 3; y++) { s.level.setBlockAndUpdate(at.offset(1, y, z), Blocks.STONE.defaultBlockState()); }
            helper.assertTrue(s.dodge(() -> true, s::within).isEmpty(), "the expected native path is rejected through a solid wall");
            helper.assertFalse(s.patch.hasMotion(), "a wall rejection starts no delayed link animation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void nativeStepRefusesAnUnsupportedLedge(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            BlockPos at = s.resident.blockPosition();
            for (int x = 1; x < 5; x++) for (int z = -3; z <= 3; z++) { s.level.setBlockAndUpdate(at.offset(x, -1, z), Blocks.AIR.defaultBlockState()); }
            helper.assertTrue(s.dodge(() -> true, s::within).isEmpty(), "a dodge cannot trade an incoming threat for a fall from unsupported ground");
            helper.assertFalse(s.patch.hasMotion(), "a ledge rejection owns no action or movement");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void authorityRevokedBeforeTheFirstAnimationTickCancelsEveryRootMotionFrame(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            AtomicBoolean allowed = new AtomicBoolean(true); Vec3 before = s.resident.position();
            s.dodge(allowed::get, s::within).orElseThrow(); allowed.set(false);
            helper.runAfterDelay(12, () -> {
                try {
                    helper.assertTrue(s.resident.position().distanceToSqr(before) < .001, "revoked intent has no delayed native displacement");
                    helper.assertFalse(s.patch.hasMotion(), "permission loss clears the actual EF action owner");
                    helper.assertFalse(s.patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation,
                        "permission loss replaces the native action and its pending link");
                } finally { s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void aChangedBoundaryCancelsBeforeTheNextNativeMovementFrame(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            AtomicBoolean boundary = new AtomicBoolean(true);
            s.dodge(() -> true, point -> boundary.get() && s.within(point)).orElseThrow();
            helper.runAfterDelay(2, () -> {
                Vec3 cancelledAt = s.resident.position(); boundary.set(false);
                helper.runAfterDelay(10, () -> {
                    try {
                        helper.assertTrue(s.resident.position().distanceToSqr(cancelledAt) < .001, "changing the live guard domain stops the next frame rather than clamping after movement");
                        helper.assertFalse(s.patch.hasMotion(), "the outdated domain no longer owns an action");
                    } finally { s.close(); }
                    helper.succeed();
                });
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void closeIsImmediateAndChangingRunnerSessionsCannotBypassTheDodgeCooldown(GameTestHelper helper) {
        Scene s = new Scene(helper);
        Scene physics = new Scene(helper, new BlockPos(4, 1, 15));
        try {
            List<String> trace = new ArrayList<>();
            trace.add(motionState(s, "before open"));
            physics.resident.setNoAi(false);
            trace.add(motionState(physics, "normal-physics control before open"));
            var controlOpened = physics.dodge(() -> true, physics::within);
            helper.assertTrue(controlOpened.isPresent(), "the independent normal-physics control starts the same native action");
            trace.add(motionState(physics, "normal-physics control before close"));
            controlOpened.get().close();
            trace.add(motionState(physics, "normal-physics control after close"));
            var opened = s.dodge(() -> true, s::within);
            helper.assertTrue(opened.isPresent(), "the grounded resident starts its first native action");
            CombatMotions.Session motion = opened.get();
            // Native ActionAnimation.begin may apply its initial coordinate. Runner ticks must add none.
            Vec3 before = s.resident.position();
            trace.add(motionState(s, "after open"));
            for (int count = 0; count < 20; count++) motion.tick();
            helper.assertValueEqual(s.resident.position(), before, "runner ticks do not script or double-apply native displacement; " + trace);
            trace.add(motionState(s, "before close"));
            motion.close();
            Vec3 closedAt = s.resident.position();
            trace.add(motionState(s, "after close"));
            motion.close();
            helper.assertValueEqual(s.resident.position(), closedAt, "repeated close cannot move the resident; " + trace);
            AtomicBoolean observing = new AtomicBoolean(true);
            int[] frames = {0};
            helper.onEachTick(() -> {
                if (!observing.get()) return;
                if (++frames[0] <= 6) {
                    trace.add(motionState(s, "cancelled frame " + frames[0]));
                    trace.add(motionState(physics, "normal-physics control frame " + frames[0]));
                }
                helper.assertFalse(s.patch.hasMotion(), "a closed work lease cannot reclaim the native action; " + trace);
                helper.assertFalse(s.patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation,
                    "closing removes both the native dodge and its pending link; " + trace);
                helper.assertFalse(physics.patch.hasMotion(), "the normal-physics control also has no action owner after close; " + trace);
                helper.assertFalse(physics.patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation,
                    "normal physics cannot leave a cancelled native dodge or link alive; " + trace);
            });
            helper.assertTrue(s.dodge(() -> true, s::within).isEmpty(), "closing and reopening another work session cannot reset the per-resident cooldown");
            helper.runAfterDelay(20, () -> {
                trace.add(motionState(s, "twenty ticks with ordinary travel disabled"));
                trace.add(motionState(physics, "normal-physics control after twenty ticks"));
                // The passing native-step test also uses NoAI: EF root movement still runs here.
                // Only ordinary travel is disabled, isolating delayed root motion from gravity/pushes.
                helper.assertTrue(s.resident.position().distanceToSqr(closedAt) < .001,
                    "closing leaves no later native root displacement when ordinary travel is isolated; " + trace);
                // Do not erase accumulated velocity or remove EF's native dodge-location indicator.
                // Restore real physics so the grounded cooldown retry tests an ordinary resident.
                s.resident.setNoAi(false);
            });
            helper.runAfterDelay(24, () -> {
                trace.add(motionState(s, "four ticks after restoring ordinary travel"));
                helper.assertTrue(s.resident.onGround(), "normal physics recovers grounding before the cooldown retry; " + trace);
                helper.assertTrue(s.level.getGameTime() < s.patch.nextDodgeAt(), "part of the forty-tick cooldown remains after ground recovery");
                helper.assertTrue(s.dodge(() -> true, s::within).isEmpty(), "a grounded new runner session cannot bypass the remaining cooldown; " + trace);
            });
            helper.runAfterDelay(41, () -> {
                try {
                    trace.add(motionState(s, "forty-one ticks after close"));
                    trace.add(motionState(physics, "normal-physics control after forty-one ticks"));
                    helper.assertTrue(s.resident.onGround(), "normal resident physics restores grounded state after cancellation; position="
                        + s.resident.position() + ", velocity=" + s.resident.getDeltaMovement() + "; " + trace);
                    helper.assertTrue(s.level.getGameTime() >= s.patch.nextDodgeAt(), "the full forty-tick cooldown has elapsed");
                    // Ordinary physics may have moved the entity; no native dodge may survive in that interval.
                    observing.set(false);
                    var next = s.dodge(() -> true, s::within);
                    helper.assertTrue(next.isPresent(), "the finite 40-tick cooldown eventually permits another actual action; motion="
                        + s.patch.hasMotion() + ", strike=" + s.patch.hasStrike() + ", blocked=" + s.patch.blocksLabor()
                        + ", pose=" + s.patch.actionOwnsPose() + ", grounded=" + s.resident.onGround() + "; " + trace);
                    next.get().close();
                    org.slf4j.LoggerFactory.getLogger("folkways-epicfight-motion-tests").info("Cancelled native-step/physics trace:\n{}", String.join("\n", trace));
                } finally { physics.close(); s.close(); }
                helper.succeed();
            });
        } catch (Throwable failure) { physics.close(); s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void savingDuringANativeStepRestoresDimensionsAndCancelsMotionBeforeSerialization(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            float standing = s.resident.getBbHeight();
            s.dodge(() -> true, s::within).orElseThrow();
            helper.runAfterDelay(2, () -> {
                Vec3 savedAt = s.resident.position();
                CompoundTag saved = s.resident.saveWithoutId(new CompoundTag());
                helper.assertFalse(s.patch.hasMotion(), "saveWithoutId revokes the motion before saving attachments");
                helper.assertValueEqual(s.resident.getBbHeight(), standing, "closing restores the native temporary dodge dimensions");
                s.resident.load(saved);
                helper.runAfterDelay(10, () -> {
                    try {
                        helper.assertTrue(s.resident.position().distanceToSqr(savedAt) < .001, "the saved action has no delayed movement after load");
                        helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "save and motion cancellation preserve one real weapon");
                        helper.assertValueEqual(ResidentEquipment.assigned(s.resident).get(DataComponents.CUSTOM_NAME), Component.literal("motion keeps actual equipment"), "equipment components and designation survive");
                    } finally { s.close(); }
                    helper.succeed();
                });
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large")
    public static void anActiveWeaponSessionPreventsDodgeWithoutChangingTheHandLoan(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            WeaponSession weapon = Weapons.open(s.worker).orElseThrow();
            try {
                ItemStack actual = s.resident.getMainHandItem();
                helper.assertTrue(s.dodge(() -> true, s::within).isEmpty(), "the runner must release its attack before starting a separate evasion action");
                helper.assertTrue(s.resident.getMainHandItem() == actual && s.resident.hasWeaponSession(), "a refused dodge does not borrow, cancel or copy the current actual weapon");
            } finally { weapon.close(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void sprintIncreasesRealNavigationDisplacementThenRestoresItsAttributeAndFlag(GameTestHelper helper) {
        Scene s = new Scene(helper);
        ResidentEntity walking = PersonBody.RESIDENT.get().create(s.level);
        try {
            s.resident.setNoAi(false); walking.setNoAi(false);
            walking.moveTo(s.resident.getX(), s.resident.getY(), s.resident.getZ() + 5); walking.setOnGround(true); s.level.addFreshEntity(walking);
            Vec3 runningStart = s.resident.position(), walkingStart = walking.position();
            helper.assertTrue(s.resident.getNavigation().moveTo(runningStart.x + 13, runningStart.y, runningStart.z, 1), "the existing runner navigation owns a real path");
            helper.assertTrue(walking.getNavigation().moveTo(walkingStart.x + 13, walkingStart.y, walkingStart.z, 1), "the comparison resident has the same ordinary path and pace");
            double speed = s.resident.getAttributeValue(Attributes.MOVEMENT_SPEED);
            var path = s.resident.getNavigation().getPath();
            CombatMotions.Session sprint = CombatMotions.open(s.worker, s.request(CombatMotions.Kind.SPRINT, () -> true, s::within)).orElseThrow();
            helper.assertTrue(s.resident.getAttributeValue(Attributes.MOVEMENT_SPEED) > speed, "sprint modifies actual native movement speed rather than only a render label");
            helper.assertTrue(s.resident.getNavigation().getPath() == path, "sprint does not replace the path owned by the existing navigator");
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(s.resident.position().distanceTo(runningStart) > walking.position().distanceTo(walkingStart) + .1,
                    "the actual sprinting resident travels farther along the existing path");
                helper.runAfterDelay(12, () -> {
                    try {
                        helper.assertFalse(s.resident.isSprinting(), "the bounded twenty-tick sprint restores the original flag");
                        helper.assertValueEqual(s.resident.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "the transient native speed modifier is removed");
                        sprint.close();
                    } finally { walking.discard(); s.close(); }
                    helper.succeed();
                });
            });
        } catch (Throwable failure) { walking.discard(); s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", timeoutTicks = 80)
    public static void revokingSprintRestoresNativeSpeedWithoutInstallingOrReplacingANavigationPath(GameTestHelper helper) {
        Scene s = new Scene(helper);
        try {
            s.resident.setNoAi(false);
            helper.assertTrue(s.resident.getNavigation().moveTo(s.center.x + 13, s.center.y, s.center.z, 1), "the existing navigation supplies the path");
            var path = s.resident.getNavigation().getPath();
            double speed = s.resident.getAttributeValue(Attributes.MOVEMENT_SPEED);
            AtomicBoolean allowed = new AtomicBoolean(true);
            CombatMotions.open(s.worker, s.request(CombatMotions.Kind.SPRINT, allowed::get, s::within)).orElseThrow();
            helper.runAfterDelay(3, () -> {
                allowed.set(false);
                helper.runAfterDelay(1, () -> {
                    try {
                        helper.assertFalse(s.resident.isSprinting(), "lost authority removes sprint before its twenty-tick budget expires");
                        helper.assertValueEqual(s.resident.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "revocation removes the actual movement-speed modifier");
                        helper.assertFalse(s.patch.hasMotion(), "the revoked request has no remaining native action owner");
                        helper.assertTrue(s.resident.getNavigation().getPath() == path, "the temporary speed adapter leaves navigation ownership with the original runner");
                    } finally { s.close(); }
                    helper.succeed();
                });
            });
        } catch (Throwable failure) { s.close(); throw failure; }
    }

    @GameTest(template = "empty_large", batch = "epicfight_motion_runner", timeoutTicks = 500)
    public static void aRealGuardRunnerDodgesTheCreeperThenYieldsItsNativeActionToANewMove(GameTestHelper helper) {
        Scene s = new Scene(helper, new BlockPos(10, 1, 10));
        var colony = Colonies.mint(s.level.getServer());
        helper.assertTrue(colony.hold(PersonContent.ID, Held.Entity.of(s.resident)).isPresent(),
            "the actual armed resident is held by the real colony");
        s.resident.joinColony(colony.id());
        s.resident.setNoAi(false);
        s.resident.getRandom().setSeed(7901);
        var commands = ResidentCommands.of(colony);
        var guard = commands.guard(s.resident, WorldSpaces.at(s.resident), new Vec3(1, 0, 0)).deliberateGuard();
        LaborFixture labor = new LaborFixture(colony, s.level);
        var creeper = EntityType.CREEPER.create(s.level);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            try { labor.close(); } finally { creeper.discard(); s.close(); colony.raze(); }
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
        });
        helper.onEachTick(() -> { if (!closed.get()) labor.tick(); });
        ResidentCommands.Order[] move = {null};
        long[] replacedAt = {0};
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING
                    && Reach.standingIn(s.resident, Stances.at(guard.destination())) && s.resident.onGround(),
                "the ordinary real runner occupies its guard slot before any threat: " + motionState(s, "guard")))
            .thenExecute(() -> {
                creeper.setNoAi(true);
                creeper.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(12, 1, 10))));
                CompoundTag saved = creeper.saveWithoutId(new CompoundTag());
                saved.putShort("Fuse", (short) 30000); saved.putBoolean("ignited", true); creeper.load(saved);
                s.level.addFreshEntity(creeper);
                helper.assertTrue(CombatReadiness.explosiveNear(s.resident).orElse(null) == creeper,
                    "the real ignited creeper is recognized while its long fuse prevents fixture destruction");
            })
            .thenWaitUntil(() -> {
                helper.assertTrue(CombatEvasion.active(s.resident, guard) && s.patch.hasMotion()
                        && s.patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation
                        && s.resident.position().subtract(s.center).horizontalDistanceSqr() > .04,
                    "Fear/CombatEvasion and the real work runner start a native EF dodge with actual displacement: "
                        + motionState(s, "evasion") + ", doing=" + s.resident.doing());
                helper.assertTrue(commands.current(s.resident.id()).orElse(null) == guard,
                    "the temporary native action retains the actual player's guard order");
                helper.assertTrue(s.resident.getNavigation().isDone(), "the native dodge runs before fallback navigation");
                helper.assertFalse(s.resident.hasWeaponSession(), "evasion has not borrowed the designated sword for an attack");
                helper.assertTrue(ResidentEquipment.assigned(s.resident) == s.weapon,
                    "the exact designated sword stays packed during the runner's native step");
            })
            .thenExecute(() -> {
                // Keep the threat alive; finish outside its eight-block danger radius after MOVE releases control.
                move[0] = commands.move(s.resident, WorldSpaces.at(s.level, helper.absolutePos(new BlockPos(19, 1, 3))));
                replacedAt[0] = s.level.getGameTime();
                helper.assertValueEqual(guard.status(), ResidentCommands.Status.RELEASED, "MOVE replaces the original guard handle");
                helper.assertFalse(CombatEvasion.active(s.resident, guard), "the previous evasion immediately loses command authority");
            })
            .thenIdle(1)
            .thenExecute(() -> {
                helper.assertTrue(s.level.getGameTime() - replacedAt[0] <= 2, "native cancellation is checked at the next frame, not eventual arrival");
                helper.assertFalse(s.patch.hasMotion(), "the replacement command revokes the native action before another root-motion frame");
                helper.assertFalse(s.patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation,
                    "the old dodge and pending native link cannot outlive the replaced order");
                helper.assertFalse(s.resident.isSprinting(), "cancellation restores the original non-sprinting flag");
            })
            .thenWaitUntil(() -> helper.assertTrue(move[0].status() == ResidentCommands.Status.ARRIVED
                    && Reach.standingIn(s.resident, Stances.at(move[0].destination())),
                "the same real runner physically reaches the new MOVE destination: " + motionState(s, "move")))
            .thenIdle(20)
            .thenExecute(() -> {
                helper.assertValueEqual(move[0].status(), ResidentCommands.Status.ARRIVED, "the actual MOVE handle records completion");
                helper.assertTrue(Reach.standingIn(s.resident, Stances.at(move[0].destination())), "arrival remains stable after relinquishing control");
                helper.assertFalse(s.patch.hasMotion(), "no cancelled native action reappears after arrival");
                helper.assertFalse(s.resident.isSprinting(), "native movement restores the original sprint state");
                helper.assertTrue(ResidentEquipment.assigned(s.resident) == s.weapon, "the original physical designated sword remains assigned");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD)
                    + (s.resident.getMainHandItem().is(Items.IRON_SWORD) ? s.resident.getMainHandItem().getCount() : 0),
                    1, "the entire threat/command lifecycle preserves exactly one real sword");
                helper.assertValueEqual(s.weapon.getDamageValue(), 7, "movement does not spend attack durability");
                helper.assertTrue(creeper.isAlive() && creeper.isIgnited(), "the live threat was never removed to make MOVE win");
                helper.assertValueEqual(s.resident.getHealth(), s.resident.getMaxHealth(), "the real threat is evaded before any injury");
            })
            .thenSucceed();
    }

    private static String motionState(Scene s, String when) {
        var animator = s.patch.getAnimator().getPlayerFor(null);
        return when + " gameTime=" + s.level.getGameTime() + " position=" + s.resident.position()
            + " delta=" + s.resident.getDeltaMovement() + " grounded=" + s.resident.onGround()
            + " noAI=" + s.resident.isNoAi() + " animation=" + animator.getAnimation().get().getClass().getSimpleName()
            + "/" + animator.getRealAnimation().get().getClass().getSimpleName()
            + " pose=" + s.resident.getPose() + " actionPose=" + s.patch.actionOwnsPose()
            + " blocked=" + s.patch.blocksLabor() + " ownedMotion=" + s.patch.hasMotion()
            + " nearby=" + s.level.getEntities(s.resident, s.resident.getBoundingBox().inflate(2)).stream().limit(8)
                .map(entity -> entity.getType() + "@" + entity.position() + ":delta=" + entity.getDeltaMovement()
                    + ":pushable=" + entity.isPushable()).toList();
    }

    private static final class Scene implements AutoCloseable {
        final ServerLevel level;
        final ResidentEntity resident;
        final ResidentEpicFightPatch patch;
        final Worker worker;
        final ItemStack weapon;
        final Vec3 center;
        Scene(GameTestHelper helper) {
            this(helper, new BlockPos(4, 1, 7));
        }
        Scene(GameTestHelper helper, BlockPos relativeFeet) {
            level = helper.getLevel();
            for (int x = 0; x < 22; x++) for (int z = 0; z < 22; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            resident = PersonBody.RESIDENT.get().create(level); resident.setNoAi(true);
            BlockPos feet = helper.absolutePos(relativeFeet); resident.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5); resident.setOnGround(true);
            weapon = new ItemStack(Items.IRON_SWORD); weapon.setDamageValue(7);
            weapon.set(DataComponents.CUSTOM_NAME, Component.literal("motion keeps actual equipment"));
            PersonalItems.mark(resident, weapon, "mainhand"); resident.pack().setItem(0, weapon);
            level.addFreshEntity(resident); center = resident.position(); patch = ResidentEpicFightPatch.of(resident);
            worker = new Worker() {
                public Resident resident() { return resident.resident(); }
                public Mob body() { return resident; }
                public Container pack() { return resident.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { resident.spawnAtLocation(stack); }
                public Effects effects() { return resident.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        boolean within(Vec3 point) { return Math.abs(point.y - center.y) < .1 && point.x > center.x - 3 && point.x < center.x + 16 && Math.abs(point.z - center.z) < 3; }
        CombatMotions.Request request(CombatMotions.Kind kind, BooleanSupplier authorized, Predicate<Vec3> within) {
            return new CombatMotions.Request(kind, new Vec3(1, 0, 0), authorized, within);
        }
        Optional<CombatMotions.Session> dodge(BooleanSupplier authorized, Predicate<Vec3> within) {
            return CombatMotions.open(worker, request(CombatMotions.Kind.DODGE, authorized, within));
        }
        @Override public void close() { CombatMotions.stop(resident); resident.discard(); }
    }
}
