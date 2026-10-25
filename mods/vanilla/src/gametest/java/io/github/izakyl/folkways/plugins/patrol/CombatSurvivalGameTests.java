package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.CombatReadiness;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.motion.CombatEvasion;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native entities and the real personal-work runner; no manually committed combat nodes. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatSurvivalGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CombatSurvivalGameTests.class); }

    @GameTest(template = "empty_large", batch = "combat_survival", timeoutTicks = 500)
    public static void aHealthyGuardEvadesAnIgnitedCreeperAndReturnsWithItsOriginalOrder(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 10, 10, false);
        helper.startSequence()
            .thenWaitUntil(f::assertHome)
            .thenExecute(() -> f.ignite(12, 10))
            .thenWaitUntil(() -> {
                helper.assertTrue(f.evading() && f.body.position().distanceToSqr(f.start) >= 4
                        && f.body.distanceTo(f.creeper) >= 4,
                    "the unhurt body really moves away from the close ignited creeper: " + f.reading());
                f.assertSameOrder();
                helper.assertTrue(f.sawSprint, "the native fallback actually sprints while walking away");
                helper.assertValueEqual(f.body.getHealth(), f.body.getMaxHealth(),
                    "evasion begins before any damage or low-health fear");
                helper.assertTrue(f.body.getLastDamageSource() == null, "no artificial hurt triggers this escape");
            })
            .thenExecute(() -> f.creeper.discard())
            .thenWaitUntil(() -> {
                f.assertHome();
                helper.assertFalse(f.evading(), "the temporary escape relinquishes the original order");
                helper.assertFalse(f.body.isSprinting(), "the original non-sprinting flag is restored");
            })
            .thenIdle(20)
            .thenExecute(() -> { f.assertHome(); f.assertSameOrder(); f.assertIntactFloor(); })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "combat_survival", timeoutTicks = 500)
    public static void aNewMoveRevokesTheOldEvasionAndActuallyReachesItsDestination(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 10, 10, false);
        ResidentCommands.Order[] move = {null};
        helper.startSequence()
            .thenWaitUntil(f::assertHome)
            .thenExecute(() -> f.ignite(12, 10))
            .thenWaitUntil(() -> helper.assertTrue(f.evading() && f.sawSprint
                    && f.body.position().distanceToSqr(f.start) > 1,
                "the old runner is physically performing evasion before replacement: " + f.reading()))
            .thenExecute(() -> {
                // Finish outside the creeper's eight-block danger radius: once MOVE arrives it
                // releases control, and an otherwise legitimate fresh autonomous escape is allowed.
                move[0] = f.commands.move(f.body, WorldSpaces.at(f.level, f.at(19, 1, 3)));
                helper.assertFalse(CombatEvasion.active(f.body, f.order), "the old evasion immediately loses authority");
                helper.assertValueEqual(f.order.status(), ResidentCommands.Status.RELEASED, "the original guard is explicitly replaced");
            })
            .thenWaitUntil(() -> helper.assertTrue(move[0].status() == ResidentCommands.Status.ARRIVED
                    && Reach.standingIn(f.body, Stances.at(move[0].destination())),
                "MOVE takes the same actual runner and reaches its real destination: " + f.reading()))
            .thenIdle(20)
            .thenExecute(() -> {
                helper.assertValueEqual(move[0].status(), ResidentCommands.Status.ARRIVED, "the captured MOVE handle records real completion");
                helper.assertTrue(f.commands.current(f.body.id()).filter(current -> current != move[0]).isEmpty(),
                    "completed MOVE may release control but evasion never reinstalls the old command");
                helper.assertTrue(Reach.standingIn(f.body, Stances.at(move[0].destination())), "the new destination stays under player control");
                helper.assertFalse(CombatEvasion.active(f.body, move[0]), "MOVE cannot start a replacement evasion even with the creeper alive");
                helper.assertFalse(f.body.isSprinting(), "cancelling the old action restores its sprint flag");
                helper.assertTrue(f.creeper.isAlive() && f.creeper.isIgnited(), "the threat was not removed to make MOVE pass");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "combat_survival", timeoutTicks = 450)
    public static void aBowGuardFindsAnUnprovokedDistantEnemyAndShootsFromItsTwelveBlockLeash(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 3, 10, true);
        Zombie[] enemy = {null};
        helper.startSequence()
            .thenWaitUntil(f::assertHome)
            .thenExecute(() -> {
                enemy[0] = f.zombie(20, 10);
                f.tracedEnemy = enemy[0];
                helper.assertTrue(f.anchor.distanceTo(enemy[0].position()) > 12
                        && f.body.distanceTo(enemy[0]) > 15,
                    "the unseen-by-old-policy enemy begins beyond both the slot leash and current bow range");
                helper.assertTrue(enemy[0].getTarget() == null && f.body.getLastHurtByMob() == null,
                    "the stationary enemy has never attacked or targeted this guard");
                var offer = f.required(f.patrol.urges(f.worker(), f.colony.view(f.level)).stream()
                    .filter(urge -> urge.id().getPath().equals("engage/" + enemy[0].getUUID())).findFirst(),
                    "the real distant enemy must produce a combat offer");
                // Inspecting the proposed cells opens no weapon and does not run/commit this node.
                Node proposed = offer.take().get();
                helper.assertTrue(!proposed.spec().stances().cells().isEmpty(), "discovery produces actual legal firing cells");
                proposed.spec().stances().cells().forEach(cell -> {
                    Vec3 point = f.required(WorldSpaces.world(f.level, cell), "the offered stance resolves in this actual realm");
                    helper.assertTrue(point.distanceToSqr(f.anchor) <= 144 && Footing.withFeetAt(f.level, cell.block(f.level)),
                        "every offered firing cell remains within the original twelve-block slot leash");
                });
            })
            .thenWaitUntil(() -> helper.assertTrue(enemy[0].getHealth() < enemy[0].getMaxHealth(),
                "the real runner walks into range, draws its real bow and hits the distant enemy: " + f.reading()))
            .thenExecute(() -> {
                helper.assertTrue(enemy[0].getLastDamageSource() != null
                        && enemy[0].getLastDamageSource().getEntity() == f.body,
                    "the actual native arrow is owned by this guard");
                helper.assertTrue(f.body.position().distanceToSqr(f.start) > 1, "the body physically changed firing position");
                helper.assertTrue(f.body.position().distanceTo(f.anchor) <= 12.9,
                    "arrival tolerance does not turn the bounded firing stance into an unlimited pursuit");
                helper.assertTrue(f.body.pack().count(Items.ARROW) < 12, "native fire consumes real packed ammunition");
                helper.assertValueEqual(f.weaponCount(), 1, "the single real bow is either held or packed, never copied");
                helper.assertTrue(f.weaponWear() > 0, "native shooting damages the actual bow");
                f.assertSameOrder(); enemy[0].discard();
            })
            .thenWaitUntil(f::assertHome)
            .thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "combat_survival", timeoutTicks = 240)
    public static void aStoppedBowGuardDoesNotUseTheExpandedDiscoveryRange(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 3, 10, true);
        f.order.close();
        f.order = f.commands.hold(f.body, WorldSpaces.at(f.body)).allowDangerRetreat();
        Zombie[] enemy = {null};
        helper.startSequence()
            .thenWaitUntil(f::assertHome)
            .thenExecute(() -> {
                enemy[0] = f.zombie(20, 10);
                helper.assertTrue(f.patrol.urges(f.worker(), f.colony.view(f.level)).stream()
                        .noneMatch(urge -> urge.id().getPath().startsWith("engage/")),
                    "STOP does not turn the new discovery radius into an offensive order");
            })
            .thenIdle(120)
            .thenExecute(() -> {
                f.assertHome(); f.assertSameOrder();
                helper.assertValueEqual(enemy[0].getHealth(), enemy[0].getMaxHealth(), "the unprovoked distant enemy remains unharmed");
                helper.assertValueEqual(f.body.pack().count(Items.ARROW), 12, "STOP spends no arrows scanning for a fight");
                helper.assertValueEqual(f.weaponWear(), 0, "STOP does not start hidden native shots");
                helper.assertFalse(f.body.hasWeaponSession(), "the runner stays with its defensive stop order");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "combat_survival", timeoutTicks = 240)
    public static void aGuardDoesNotExpandDiscoveryToAnUnangryNeutralMob(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 3, 10, true);
        Mob[] neutral = {null};
        helper.startSequence()
            .thenWaitUntil(f::assertHome)
            .thenExecute(() -> {
                neutral[0] = f.spawn(EntityType.ZOMBIFIED_PIGLIN, 17, 10);
                helper.assertTrue(neutral[0].getTarget() == null, "the neutral mob has not chosen a hostile target");
                helper.assertTrue(f.patrol.urges(f.worker(), f.colony.view(f.level)).stream()
                        .noneMatch(urge -> urge.id().getPath().startsWith("engage/")),
                    "the wider guard discovery preserves the neutral-hostility policy");
            })
            .thenIdle(120)
            .thenExecute(() -> {
                f.assertHome(); f.assertSameOrder();
                helper.assertValueEqual(neutral[0].getHealth(), neutral[0].getMaxHealth(), "the real neutral entity remains unhurt");
                helper.assertValueEqual(f.body.pack().count(Items.ARROW), 12, "guarding does not shoot neutral mobs first");
                helper.assertFalse(f.body.hasWeaponSession(), "no attack session silently takes control");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "combat_survival", timeoutTicks = 350)
    public static void aBlockedEvasionEndsWithinItsDeadlineWithoutCrossingWallsOrACliff(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 10, 10, false);
        BlockPos[] refuge = {null};
        long[] blockedAt = {0};
        helper.startSequence()
            .thenWaitUntil(f::assertHome)
            .thenExecute(() -> f.ignite(12, 10))
            .thenWaitUntil(() -> helper.assertTrue(f.evading() && f.sawSprint
                    && f.body.position().distanceToSqr(f.start) > 1,
                "the real escape is already moving before its route becomes blocked: " + f.reading()))
            .thenExecute(() -> {
                refuge[0] = f.body.blockPosition();
                blockedAt[0] = f.level.getGameTime();
                // Keep a three-cell refuge. A four-high wall and the surrounding unsupported gap
                // remove every outward walk; no teleport, command replacement or completed-work flag.
                for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                    BlockPos feet = refuge[0].offset(dx, 0, dz);
                    if (Math.max(Math.abs(dx), Math.abs(dz)) > 2
                            && !feet.below().equals(f.creeper.blockPosition().below())) {
                        f.level.setBlockAndUpdate(feet.below(), Blocks.AIR.defaultBlockState());
                    }
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                        for (int y = 0; y < 4; y++) f.level.setBlockAndUpdate(feet.above(y), Blocks.STONE.defaultBlockState());
                    }
                }
                f.checkRefuge = refuge[0];
            })
            .thenWaitUntil(() -> {
                helper.assertTrue(!f.evading() && !f.avoidingExplosion() && !f.body.isSprinting(),
                    "the unreachable finite escape actually releases its runner and sprint state: " + f.reading());
                helper.assertTrue(f.level.getGameTime() - blockedAt[0] <= 110,
                    "an evasion cannot live beyond its own hundred-tick budget after obstruction");
            })
            .thenIdle(30)
            .thenExecute(() -> {
                f.assertSameOrder();
                helper.assertTrue(Footing.withFeetAt(f.level, f.body.blockPosition()), "the body stays on real supported footing");
                helper.assertValueEqual(f.body.getHealth(), f.body.getMaxHealth(), "the body neither falls nor phases through the wall");
                helper.assertFalse(f.evading(), "the wall does not trigger an endless chain of unreachable evasions");
            }).thenSucceed();
    }

    private static final class Fixture implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        final ResidentCommands commands;
        final PatrolPresence patrol;
        final LaborFixture labor;
        final List<Mob> enemies = new ArrayList<>();
        final Vec3 start;
        final Vec3 anchor;
        final boolean bow;
        ResidentCommands.Order order;
        Creeper creeper;
        BlockPos checkRefuge;
        boolean sawSprint;
        boolean closed;
        Mob tracedEnemy;
        long nextTrace;
        final Set<UUID> tracedArrows = new HashSet<>();

        Fixture(GameTestHelper helper, int x, int z, boolean bow) {
            this.helper = helper; this.level = helper.getLevel(); this.bow = bow;
            for (int xx = 0; xx < 22; xx++) for (int zz = 0; zz < 22; zz++) {
                helper.setBlock(new BlockPos(xx, 0, zz), Blocks.STONE);
            }
            colony = Colonies.mint(level.getServer());
            body = PersonBody.RESIDENT.get().create(level);
            helper.assertTrue(body != null, "the registered resident type creates the actual test body");
            body.moveTo(Vec3.atBottomCenterOf(at(x, 1, z)));
            body.getRandom().setSeed(7901);
            body.pack().insert(new ItemStack(bow ? Items.BOW : Items.IRON_SWORD));
            if (bow) body.pack().insert(new ItemStack(Items.ARROW, 12));
            level.addFreshEntity(body);
            helper.assertTrue(colony.hold(PersonContent.ID, Held.Entity.of(body)).isPresent(), "the actual body is admitted to this colony");
            body.joinColony(colony.id());
            start = body.position();
            commands = ResidentCommands.of(colony);
            order = commands.guard(body, WorldSpaces.at(body), new Vec3(1, 0, 0)).deliberateGuard();
            anchor = required(WorldSpaces.world(level, order.destination()), "the original order has an actual world destination");
            patrol = required(colony.service(PatrolContent.ID, PatrolPresence.class), "this colony owns its actual patrol service");
            labor = new LaborFixture(colony, level);
            helper.testInfo.addListener(this);
            helper.onEachTick(() -> {
                if (closed) return;
                labor.tick();
                sawSprint |= body.isSprinting();
                if (tracedEnemy != null) traceShots();
                if (checkRefuge != null) {
                    helper.assertTrue(Math.abs(body.getX() - (checkRefuge.getX() + .5)) < 1.75
                            && Math.abs(body.getZ() - (checkRefuge.getZ() + .5)) < 1.75
                            && body.getY() >= checkRefuge.getY() - .1,
                        "failed evasion never crosses its enclosing wall or falls into the surrounding gap: " + reading());
                }
            });
        }

        BlockPos at(int x, int y, int z) { return helper.absolutePos(new BlockPos(x, y, z)); }
        <T extends Mob> T spawn(EntityType<T> type, int x, int z) {
            T mob = type.create(level);
            helper.assertTrue(mob != null, "the registered enemy type creates the actual test entity");
            mob.moveTo(Vec3.atBottomCenterOf(at(x, 1, z))); mob.setNoAi(true);
            mob.getRandom().setSeed(7902);
            enemies.add(mob); level.addFreshEntity(mob); return mob;
        }
        Zombie zombie(int x, int z) {
            Zombie mob = spawn(EntityType.ZOMBIE, x, z);
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60); mob.setHealth(60);
            return mob;
        }
        void ignite(int x, int z) {
            creeper = spawn(EntityType.CREEPER, x, z);
            CompoundTag saved = creeper.saveWithoutId(new CompoundTag());
            saved.putShort("Fuse", (short) 30000); saved.putBoolean("ignited", true); creeper.load(saved);
            helper.assertTrue(creeper.isIgnited() && CombatReadiness.explosiveNear(body).orElse(null) == creeper,
                "an actual ignited no-AI creeper is recognized without dealing damage");
        }
        boolean evading() { return CombatEvasion.active(body, order); }
        boolean avoidingExplosion() {
            return body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath().equals("avoiding_explosion")).orElse(false);
        }
        void assertSameOrder() {
            helper.assertTrue(commands.current(body.id()).orElse(null) == order, "temporary combat work retains the original command handle");
        }
        <T> T required(Optional<T> value, String message) {
            helper.assertTrue(value.isPresent(), message);
            return value.get();
        }
        void assertHome() {
            assertSameOrder();
            helper.assertTrue(order.status() == ResidentCommands.Status.HOLDING
                    && Reach.standingIn(body, Stances.at(order.destination())), "the actual body occupies its commanded slot: " + reading());
        }
        int weaponCount() {
            return body.pack().count(bow ? Items.BOW : Items.IRON_SWORD)
                + (body.getMainHandItem().is(bow ? Items.BOW : Items.IRON_SWORD) ? body.getMainHandItem().getCount() : 0);
        }
        int weaponWear() {
            return body.pack().contents().stream().filter(stack -> stack.is(bow ? Items.BOW : Items.IRON_SWORD))
                .mapToInt(ItemStack::getDamageValue).sum()
                + (body.getMainHandItem().is(bow ? Items.BOW : Items.IRON_SWORD) ? body.getMainHandItem().getDamageValue() : 0);
        }
        void assertIntactFloor() {
            helper.assertTrue(level.getBlockState(at(10, 0, 10)).is(Blocks.STONE)
                    && level.getBlockState(at(12, 0, 10)).is(Blocks.STONE), "the long real fuse prevents an explosion from destroying the fixture");
        }
        String reading() {
            return "body=" + body.id() + ", feet=" + body.position() + ", order=" + order.id() + "/" + order.status()
                + ", doing=" + body.doing() + ", evade=" + evading() + ", sprint=" + body.isSprinting()
                + ", navDone=" + body.getNavigation().isDone() + ", ammo=" + body.pack().count(Items.ARROW)
                + ", wear=" + weaponWear() + ", session=" + body.hasWeaponSession()
                + (tracedEnemy == null ? "" : ", enemy=" + tracedEnemy.position() + ", hp=" + tracedEnemy.getHealth()
                    + ", distance=" + body.distanceTo(tracedEnemy) + ", sight=" + body.hasLineOfSight(tracedEnemy));
        }
        void traceShots() {
            if (level.getGameTime() >= nextTrace) {
                nextTrace = level.getGameTime() + 20;
                org.slf4j.LoggerFactory.getLogger("folkways-combat-survival-test")
                    .info("ranged tick={} {}", level.getGameTime(), reading());
            }
            for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, body.getBoundingBox().inflate(32),
                    projectile -> projectile.getOwner() == body)) {
                Vec3 offset = arrow.position().subtract(tracedEnemy.position()).multiply(1, 0, 1);
                if (offset.lengthSqr() <= 9 && tracedArrows.add(arrow.getUUID())) {
                    org.slf4j.LoggerFactory.getLogger("folkways-combat-survival-test")
                        .info("arrow tick={} id={} feet={} velocity={} target={} targetHeight={}",
                            level.getGameTime(), arrow.getUUID(), arrow.position(), arrow.getDeltaMovement(),
                            tracedEnemy.position(), tracedEnemy.getBbHeight());
                }
            }
        }
        Worker worker() {
            return new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return body.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) {
                    return body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst().orElse(ItemStack.EMPTY);
                }
                public void spill(ItemStack stack) { throw new AssertionError("combat survival never fabricates/spills goods"); }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        void close() {
            if (closed) return;
            closed = true;
            try { labor.close(); } finally {
                body.discard(); enemies.forEach(Mob::discard); colony.raze();
            }
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
