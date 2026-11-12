package io.github.izakyl.folkways.core.rts.combat.command;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import io.github.izakyl.folkways.vanilla.pasture.PastureContent;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.fear.FearContent;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class ResidentCommandGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentCommandGameTests.class);
    }

    @GameTest(batch = "rts_command_handoff", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void ordersEndWorkKeepCargoAndToolsAndReplanTheLatestWork(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        body.pack().insert(new ItemStack(Items.IRON_INGOT, 8));
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(7);
        body.pack().insert(axe);
        CommandJob original = new CommandJob(f.at(2, 1, 2), body);
        CommandJob replacement = new CommandJob(f.at(2, 1, 2), body);
        AtomicReference<ResidentCommands.Order> order = new AtomicReference<>();
        original.submit(f.colony, f.level);
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    original.working.get(),
                    "the real runner started the original job; stalls="
                    + f.colony.stallsIn(f.level.dimension())
                    + "; doing="
                    + body.doing()))
            .thenExecute(() -> order.set(f.commands.hold(body, f.at(12, 1, 2))))
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    order.get().status() == ResidentCommands.Status.HOLDING,
                    "the order reaches its destination through Core travel"))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    original.released.get(), 1, "work is released exactly once on takeover");
                CommandJob.assertCarried(helper, body);
                f.colony.withdraw(CommandJob.WORK, original.id());
                replacement.submit(f.colony, f.level);
            })
            .thenIdle(15)
            .thenExecute(
            () -> {
                helper.assertFalse(
                    replacement.working.get(),
                    "labour cannot navigate while the command holds the body");
                order.get().close();
            })
            .thenWaitUntil(
            () -> helper.assertTrue(replacement.working.get(), "release lets normal planning assign the latest work"))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    original.committed.get(), 0, "the superseded job is never replayed");
                CommandJob.assertCarried(helper, body);
                replacement.finish.set(true);
            })
            .thenWaitUntil(
            () -> helper.assertValueEqual(replacement.committed.get(), 1, "replacement work commits once"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void moveFinishesAndRetreatHoldsUntilReleased(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order move = f.commands.move(body, f.at(8, 1, 2));
        AtomicReference<ResidentCommands.Order> retreat = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(
            () -> helper.assertTrue(move.status() == ResidentCommands.Status.ARRIVED, "move completes"))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    f.commands.current(body.id()).isEmpty(), "finished movement releases control");
                retreat.set(f.commands.retreat(body, f.at(2, 1, 2)));
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    retreat.get().status() == ResidentCommands.Status.HOLDING,
                    "retreat reaches and holds its safe destination"))
            .thenIdle(20)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    body.mob().distanceToSqr(Vec3.atBottomCenterOf(f.at(2, 1, 2).block(f.level))) < 2,
                    "the body stays at the retreat destination");
                body.mob().setDeltaMovement(1.2, .3, 0);
            })
            .thenIdle(10)
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    Reach.standingAt(body.mob().position(), f.at(2, 1, 2).block(f.level)),
                    "a held resident displaced by an impulse walks back to its stance"))
            .thenExecute(
            () -> {
                retreat.get().close();
                helper.assertTrue(
                    f.commands.current(body.id()).isEmpty(), "release relinquishes the command");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 650)
    public static void aWoundedGuardYieldsToFearAndRetreatStillTakesPriorityOverCombat(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(10, 1, 5));
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        body.mob().getRandom().setSeed(42L);
        Zombie enemy = EntityType.ZOMBIE.create(f.level);
        enemy.setNoAi(true);
        enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        BlockPos at = f.at(13, 1, 5).block(f.level);
        enemy.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        f.level.addFreshEntity(enemy);
        f.enemies.add(enemy);
        ResidentCommands.Order guard = f.commands.guard(body, f.at(10, 1, 5), new Vec3(1, 0, 0));
        AtomicReference<ResidentCommands.Order> retreat = new AtomicReference<>();
        AtomicReference<Float> health = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    enemy.getHealth() < enemy.getMaxHealth(),
                    "a healthy guard initiates a real weapon strike"))
            .thenExecute(
            () -> {
                body.mob().hurt(f.level.damageSources().mobAttack(enemy), 1);
                body.mob().setHealth(body.mob().getMaxHealth() * .25F);
                enemy.setTarget(body.mob());
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    body.doing()
                    .filter(
                    doing ->
                        doing.toward()
                            .orElse(doing.what())
                            .equals(FearContent.FLEEING))
                    .isPresent(),
                    "low-health fear interrupts both the guard slot and its combat urge"))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    f.commands.current(body.id()).orElseThrow() == guard,
                    "fear temporarily yields the same guard handle rather than deleting its return slot");
                retreat.set(f.commands.retreat(body, f.at(4, 1, 5)));
                helper.assertFalse(guard.engaged(), "a replaced guard cannot retain an engagement lease");
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    retreat.get().status() == ResidentCommands.Status.HOLDING,
                    "explicit retreat safely stops combat and fear and walks to its requested point"))
            .thenExecute(() -> health.set(enemy.getHealth()))
            .thenIdle(30)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    enemy.getHealth(), health.get(), "retreat keeps holding without new strikes");
                retreat.get().close();
                helper.assertTrue(
                    f.commands.current(body.id()).isEmpty(),
                    "release relinquishes the retreat command");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 220)
    public static void aBowGuardCancelsAnEscapingTargetBeforeDrawingOnAnotherEnemy(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(7, 1, 2));
        body.pack().insert(new ItemStack(Items.BOW));
        body.pack().insert(new ItemStack(Items.ARROW, 8));
        arm(f.colony, body, "Bow", ItemSpec.of(Items.BOW));
        Zombie first = EntityType.ZOMBIE.create(f.level);
        Zombie second = EntityType.ZOMBIE.create(f.level);
        for (Zombie enemy : List.of(first, second)) {
            enemy.setNoAi(true);
            enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            f.enemies.add(enemy);
        }
        BlockPos near = f.at(13, 1, 2).block(f.level);
        BlockPos other = f.at(7, 1, 10).block(f.level);
        first.moveTo(near.getX() + .5, near.getY(), near.getZ() + .5);
        second.moveTo(other.getX() + .5, other.getY(), other.getZ() + .5);
        f.level.addFreshEntity(first);
        f.level.addFreshEntity(second);
        ResidentCommands.Order guard = f.commands.guard(body, f.at(7, 1, 2), new Vec3(1, 0, 0));
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    body.mob().isUsingItem() && body.mob().getUseItem().is(Items.BOW),
                    "the real runner starts drawing at the nearest enemy"))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    first.getHealth(), first.getMaxHealth(), "the first draw has not discharged");
                BlockPos outside = f.at(20, 1, 2).block(f.level);
            // Thirteen blocks leaves the guard's twelve-block leash but remains
            // inside bow range.
                first.moveTo(outside.getX() + .5, outside.getY(), outside.getZ() + .5);
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    second.getHealth() < second.getMaxHealth(),
                    "the in-leash enemy receives a real arrow after the original draw is cancelled"))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    first.getHealth(),
                    first.getMaxHealth(),
                    "a different enemy cannot keep the escaping target's old attack alive");
                helper.assertValueEqual(
                    body.pack().count(Items.ARROW),
                    7,
                    "the cancelled escaping-target draw spends no arrow");
                helper.assertTrue(
                    f.commands.current(body.id()).orElseThrow() == guard,
                    "switching enemies preserves the same guard return order");
                guard.close();
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 1900)
    public static void repeatedFearInterruptionsDoNotConsumeTheGuardsTravelTimeout(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 5));
        body.mob().getRandom().setSeed(42L);
        WorldPos slot = f.at(18, 1, 5);
        ResidentCommands.Order guard = f.commands.guard(body, slot, new Vec3(1, 0, 0));
        AtomicBoolean burning = new AtomicBoolean();
        AtomicInteger burns = new AtomicInteger();
        helper.onEachTick(
            () -> {
                if (burning.get() && f.level.getGameTime() % 20 == 0) {
                    body.mob().hurt(f.level.damageSources().campfire(), .01F);
                    burns.incrementAndGet();
                }
            });
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    guard.status() == ResidentCommands.Status.MOVING
                    && body.mob().getX() > f.at(3, 1, 5).block(f.level).getX() + .5
                    && !Reach.standingAt(body.mob().position(), slot.block(f.level)),
                    "the guard physically starts its long trip and has not arrived when danger begins"))
            .thenExecute(
            () -> {
                burning.set(true);
                body.mob().hurt(f.level.damageSources().campfire(), .01F);
                burns.incrementAndGet();
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    body.doing()
                    .filter(
                    doing ->
                        doing.toward()
                            .orElse(doing.what())
                            .equals(FearContent.FLEEING))
                    .isPresent()
                    && guard.status() == ResidentCommands.Status.MOVING
                    && !Reach.standingAt(body.mob().position(), slot.block(f.level)),
                    "actual repeated fire damage makes fear take the runner away from the guard slot"))
            .thenIdle(1280)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    burns.get() >= 60, "more than a minute of repeated danger has really elapsed");
                helper.assertTrue(
                    body.mob().isAlive(), "tiny real burns keep the long interruption nonlethal");
                helper.assertTrue(
                    f.commands.current(body.id()).filter(order -> order == guard).isPresent()
                    && guard.status() != ResidentCommands.Status.FAILED,
                    "time spent in fear must not exhaust the guard's finite travel budget");
                burning.set(false);
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    guard.status() == ResidentCommands.Status.HOLDING
                    && Reach.standingAt(body.mob().position(), slot.block(f.level)),
                    "after danger ends the same guard handle returns through the same runner"))
            .thenExecute(
            () -> {
                guard.close();
                helper.assertTrue(
                    f.commands.current(body.id()).isEmpty(),
                    "release relinquishes the recovered guard");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 260)
    public static void replacingATripCannotBeCancelledByItsOldHandle(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order old = f.commands.hold(body, f.at(18, 1, 2));
        AtomicReference<ResidentCommands.Order> next = new AtomicReference<>();
        helper.startSequence()
            .thenIdle(8)
            .thenExecute(
            () -> {
                next.set(f.commands.retreat(body, f.at(2, 1, 2)));
                old.close();
                helper.assertTrue(
                    f.commands.current(body.id()).orElseThrow() == next.get(),
                    "the old release token cannot cancel its replacement");
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    next.get().status() == ResidentCommands.Status.HOLDING,
                    "a replacement interrupts the trip and takes effect"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 120)
    public static void deathAndColonyClosureReleaseCommandHandles(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body dying = f.resident(new BlockPos(2, 1, 2));
        Body other = f.resident(new BlockPos(5, 1, 2));
        dying.pack().insert(new ItemStack(Items.IRON_INGOT, 8));
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(7);
        dying.pack().insert(axe);
        dying.presentHeldItem(axe.copy());
        ResidentCommands.Order dead = f.commands.hold(dying, f.at(2, 1, 2));
        ResidentCommands.Order closing = f.commands.hold(other, f.at(5, 1, 2));
        helper.runAfterDelay(5, () -> dying.mob().kill());
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    dead.status() == ResidentCommands.Status.RELEASED,
                    "death releases the command without waiting for a trip timeout"))
            .thenExecute(
            () -> {
                var drops =
                    f.level.getEntitiesOfClass(
                    ItemEntity.class, dying.mob().getBoundingBox().inflate(2));
                helper.assertValueEqual(
                    drops.stream()
                    .filter(item -> item.getItem().is(Items.IRON_INGOT))
                    .mapToInt(item -> item.getItem().getCount())
                    .sum(),
                    8,
                    "death drops each material exactly once");
                helper.assertValueEqual(
                    drops.stream()
                    .filter(item -> item.getItem().is(Items.IRON_AXE))
                    .mapToInt(item -> item.getItem().getCount())
                    .sum(),
                    1,
                    "the presentation copy creates no duplicate tool");
                f.colony.raze();
                helper.assertTrue(
                    closing.status() == ResidentCommands.Status.RELEASED,
                    "colony close releases holds");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void deliberatePatrolGuardRetainsControlAfterItsRealWeaponLoanFinishes(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order guard = f.commands.guard(body, f.at(2, 1, 2), new Vec3(1, 0, 0));
        helper.assertValueEqual(
            f.commands.priority(guard), Urge.Priority.ORDINARY, "legacy guard priority is unchanged");
        guard.deliberateGuard();
        helper.assertValueEqual(
            f.commands.priority(guard),
            Urge.Priority.CONTROL,
            "a player patrol stop keeps priority over unrelated personal needs");
        guard.engaging();
        helper.assertValueEqual(
            f.commands.priority(guard),
            Urge.Priority.ORDINARY,
            "an actual engagement permits combat to borrow the same runner");
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        WeaponAttack attack = Weapons.open(commandWorker(body)).orElseThrow();
        // Exercise the autosave boundary regardless of when this test's batch reaches server tick 6000.
        CompoundTag saved = body.mob().saveWithoutId(new CompoundTag());
        Body loaded = PersonBody.RESIDENT.get().create(f.level);
        loaded.mob().load(saved);
        helper.assertValueEqual(loaded.pack().count(Items.IRON_SWORD), 1, "loading returns the saved real weapon once");
        helper.assertTrue(loaded.mob().getMainHandItem().isEmpty(), "loading leaves no unowned hand duplicate");
        helper.runAfterDelay(
            21,
            () -> {
                try {
                    helper.assertFalse(guard.engaged(), "the short combat detection lease has elapsed");
                    helper.assertValueEqual(
                        f.commands.priority(guard),
                        Urge.Priority.ORDINARY,
                        "the actual hand loan prevents lease expiry from repeatedly cancelling a longer native attack");
                    attack.stop(); Weapons.holster(commandWorker(body));
                    helper.assertValueEqual(
                        f.commands.priority(guard),
                        Urge.Priority.CONTROL,
                        "the patrol reclaims control as soon as both combat lease and actual loan are over");
                } finally {
                    attack.stop(); Weapons.holster(commandWorker(body));
                }
                helper.succeed();
            });
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void aDeliberateGuardYieldsToLowHealthDangerButNotLowHealthAlone(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order guard = f.commands.guard(body, f.at(2, 1, 2), new Vec3(1, 0, 0)).deliberateGuard();
        body.mob().setHealth(body.mob().getMaxHealth() * .25F);
        helper.assertValueEqual(
            f.commands.priority(guard),
            Urge.Priority.CONTROL,
            "low health without danger does not allow unrelated needs to take over a player patrol");
        Zombie enemy = EntityType.ZOMBIE.create(f.level);
        enemy.setNoAi(true);
        enemy.moveTo(body.mob().getX() + 2, body.mob().getY(), body.mob().getZ());
        f.level.addFreshEntity(enemy);
        f.enemies.add(enemy);
        enemy.setTarget(body.mob());
        body.mob().setLastHurtByMob(enemy);
        helper.assertValueEqual(
            f.commands.priority(guard),
            Urge.Priority.ORDINARY,
            "a wounded guard in real danger permits the existing fear urge to borrow its runner");
        enemy.setTarget(null);
        helper.assertValueEqual(
            f.commands.priority(guard),
            Urge.Priority.CONTROL,
            "once the danger is gone the deliberate stop regains its control tier");
        enemy.setTarget(body.mob());
        guard.close();
        helper.assertFalse(
            guard.yieldingToDanger(),
            "a released handle cannot keep a replacement squad paused by its old danger state");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void formationHoldRegainsControlAfterRecentFireExpires(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order hold = f.commands.hold(body, f.at(2, 1, 2)).allowDangerRetreat();
        body.mob().hurt(f.level.damageSources().onFire(), 1);
        body.mob().setHealth(body.mob().getMaxHealth() * .25F);
        helper.assertTrue(hold.yieldingToDanger(), "a wounded formation member yields to actual recent fire");
        helper.assertValueEqual(
            f.commands.priority(hold),
            Urge.Priority.ORDINARY,
            "recent danger lowers control priority so fear can borrow the same runner");
        helper.assertTrue(
            f.commands.urges(commandWorker(body)).getFirst().weight() < Urge.DIRE,
            "the command's numeric weight also yields to fear");
        helper.startSequence()
            .thenIdle(41)
            .thenExecute(
            () ->
                helper.assertTrue(
                    body.mob().getLastDamageSource() == null,
                    "Minecraft expires a previous fire source after forty game ticks"))
            .thenWaitUntil(
            () ->
                helper.assertFalse(
                    hold.yieldingToDanger(),
                    "after the existing finite fear escape finishes, old fire cannot pause regrouping indefinitely"))
            .thenExecute(
            () ->
                helper.assertValueEqual(
                    f.commands.priority(hold),
                    Urge.Priority.CONTROL,
                    "low health alone does not prevent control from returning"))
            .thenSucceed();
    }

    private static Worker commandWorker(Body body) {
        return TestWorker.of(body).spill(stack -> body.mob().spawnAtLocation(stack));
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void invalidCommandsDoNotReplaceAnExistingOrder(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order order = f.commands.hold(body, f.at(2, 1, 2));
        try {
            f.commands.move(body, f.at(1000, 1, 2));
            helper.fail("unloaded or distant movement must be rejected");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(
                f.commands.current(body.id()).orElseThrow() == order,
                "reject before replacing the existing command");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void retreatFromAVanillaStationSeatValidatesBeforeReleasingIt(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        Entity seat = EntityType.BOAT.create(f.level);
        seat.moveTo(body.mob().position());
        f.level.addFreshEntity(seat);
        WeaponStations.Station station =
            new WeaponStations.Station() {
                public WorldPos at() {
                    return f.at(2, 1, 2);
                }

                public double range() {
                    return 32;
                }

                public boolean indirect() {
                    return false;
                }

                public ItemSpec ammunition() {
                    return ItemSpec.of(Items.ARROW);
                }

                public boolean operate(Worker operator, SpottedTargets.Spot observation) {
                    return false;
                }

                public void release(Worker operator) {
                }
            };
        try (WeaponStations.Lease lease = WeaponStations.acquire(station, commandWorker(body)).orElseThrow()) {
            helper.assertTrue(lease.mount(seat), "Vanilla owns the temporary station seat");
            try {
                f.commands.retreat(body, f.at(1000, 1, 2));
                helper.fail("a distant retreat must be refused");
            } catch (IllegalArgumentException expected) {
                helper.assertTrue(body.mob().getVehicle() == seat, "validation failure preserves the active seat");
            }
            f.commands.retreat(body, f.at(3, 1, 2));
            helper.assertFalse(body.mob().isPassenger(), "a valid retreat immediately releases the station seat");
            helper.assertTrue(f.commands.current(body.id()).isPresent(), "the retreat owns command authority");
        } finally {
            seat.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void moveOrderRidesTheAssignedHorseAndHoldsItsPostMounted(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 4));
        var front = ColonyFront.of(f.colony);
        front.addZone(f.level, PastureContent.PASTURE,
            Set.of(f.at(4, 0, 4).cell()),
            ColonySettings.empty()
                .with("animal", new ColonySettings.Value.Choice(
                    ResourceLocation.withDefaultNamespace("horse")))).orElseThrow();
        var horse = EntityType.HORSE.create(f.level);
        horse.moveTo(f.at(4, 1, 4).cell().getBottomCenter());
        horse.setTamed(true);
        f.level.addFreshEntity(horse);
        var supplies = PatrolSupplies.of(f.colony);
        var created = supplies.profiles().create("Mounted move");
        supplies.profiles().replace(new LoadoutProfile(created.id(),
            created.name(), List.of(LoadoutProfile.Entry.mount())));
        body.pack().insert(new ItemStack(Items.SADDLE));
        supplies.profiles().assign(List.of(body.id()), Optional.of(created.id()));
        AtomicBoolean rode = new AtomicBoolean();
        var order = f.commands.hold(body, f.at(18, 1, 4));
        helper.onEachTick(() -> rode.set(rode.get() || body.mob().getVehicle() == horse));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(order.status() == ResidentCommands.Status.HOLDING,
            "RTS movement reaches the destination through the riding gait: " + order.status()
                + " failure=" + order.failure() + " at=" + body.mob().position() + " horse=" + horse.position()
                + " rode=" + rode.get() + " doing=" + body.doing()))
            .thenExecute(() -> {
                helper.assertTrue(rode.get() && horse.getX() > f.at(12, 1, 4).cell().getX(),
                    "the move order steers the real horse");
                helper.assertTrue(body.mob().getVehicle() == horse, "the resident holds its post in the saddle");
                order.close();
                horse.discard();
            }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final LaborFixture simulation;
        final List<Body> bodies = new ArrayList<>();
        final List<Zombie> enemies = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony);
            simulation = new LaborFixture(colony, level);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 12; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            Cleanup.on(helper, this::close);
            helper.onEachTick(simulation::tick);
        }

        WorldPos at(int x, int y, int z) {
            return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z)));
        }

        Body resident(BlockPos at) {
            Body body = PersonBody.RESIDENT.get().create(level);
            BlockPos absolute = helper.absolutePos(at);
            body.mob().moveTo(absolute.getX() + .5, absolute.getY(), absolute.getZ() + .5);
            level.addFreshEntity(body.mob());
            colony.hold(PersonContent.ID, Claimed.Entity.of(body.mob())).orElseThrow();
            body.joinColony(colony.id());
            arm(colony, body, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
            bodies.add(body);
            return body;
        }

        void close() {
            simulation.close();
            bodies.forEach(body -> body.mob().discard());
            enemies.forEach(Entity::discard);
            colony.raze();
        }
    }

    private static void arm(Colony colony, Body body, String name, ItemSpec weapon) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile =
            profiles.all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseGet(() -> profiles.add(profiles.create(name).id(), LoadoutProfile.Entry.weapon(weapon)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }

    private static void assignLoadout(
        PatrolSupplies supplies,
        Body body, String name) {
        var profiles = supplies.profiles();
        var profile = profiles.all().stream().filter(value -> value.name().equals(name)).findFirst()
            .orElseGet(() -> {
                var created = profiles.create(name);
                var weapon = switch (name) {
                    case "melee" -> ItemSpec.of(
                        Tags.Items.MELEE_WEAPON_TOOLS);
                    case "bow" -> ItemSpec.of(Items.BOW);
                    case "crossbow" -> ItemSpec.of(Items.CROSSBOW);
                    default -> throw new IllegalArgumentException(name);
                };
                var entries = new ArrayList<LoadoutProfile.Entry>();
                entries.add(LoadoutProfile.Entry.weapon(weapon));
                if (!name.equals("melee")) {
                    entries.add(LoadoutProfile.Entry.supply(
                        ItemSpec.of(ItemTags.ARROWS), 8, 8));
                }
                profiles.replace(new LoadoutProfile(
                    created.id(), created.name(), entries));
                return profiles.get(created.id()).orElseThrow();
            });
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
}
