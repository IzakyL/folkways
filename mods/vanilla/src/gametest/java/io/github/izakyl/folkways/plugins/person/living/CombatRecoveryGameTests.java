package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = io.github.izakyl.folkways.plugins.FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatRecoveryGameTests {
    private static final int STARTING_FOOD = 17;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CombatRecoveryGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 520)
    public static void guardRecoveryEatsRealBreadAndKeepsTheSameOrder(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1);
        ResidentCommands.Order guard = f.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                "the guard reaches its assigned slot before recovery is offered"))
            .thenExecute(() -> {
                f.exhaustTo(STARTING_FOOD);
                f.body.mob().setHealth(10.0F);
                helper.assertValueEqual(f.foodLevel(), STARTING_FOOD,
                    "the injured guard starts at fullness 17, above vanilla bread's ordinary meal threshold");
                helper.assertFalse(f.living.wouldEat(f.body.id(), new ItemStack(Items.BREAD).getFoodProperties(f.body.mob())),
                    "ordinary bread is not a meal at fullness 17 before recovery starts");
            })
            .thenIdle(120)
            .thenWaitUntil(() -> helper.assertTrue(eating(f.body),
                "after the quiet period, the real labour runner starts the in-place recovery meal"))
            .thenExecute(() -> {
                helper.assertTrue(f.living.wouldEat(f.body.id(), new ItemStack(Items.BREAD).getFoodProperties(f.body.mob())),
                    "the recovery-only meal context accepts the same bread at fullness 17");
                helper.assertValueEqual(f.body.pack().count(Items.BREAD), 1,
                    "the actual bread remains until the 32-tick meal finishes");
                f.assertCurrent(guard, "recovery lends the same guard order its existing runner");
            })
            .thenWaitUntil(() -> helper.assertTrue(f.body.pack().count(Items.BREAD) == 0
                    && f.body.mob().getHealth() > 10.0F,
                "native eating consumes one real bread and natural regeneration heals the resident"))
            .thenExecute(() -> {
                f.assertCurrent(guard, "finishing recovery preserves the same guard handle");
                helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                    "the guard resumes and continues holding its original slot");
                var alertness = f.living.alertnessOf(f.body.id());
                helper.assertTrue(alertness.isPresent() && alertness.get() < Rest.SPENT,
                    "the controlled resident keeps accumulating awake fatigue during recovery");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void missingRationsDoNotCreateARecoveryMeal(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 0);
        ResidentCommands.Order guard = f.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                "the guard is stable before checking its rations"))
            .thenExecute(() -> { f.exhaustTo(STARTING_FOOD); f.body.mob().setHealth(10.0F); })
            .thenIdle(130)
            .thenExecute(() -> {
                helper.assertTrue(f.recoveryStatus().endsWith(".missing_food"),
                    "the resident reports missing personal rations");
                helper.assertFalse(eating(f.body), "no recovery meal starts without food");
                f.assertCurrent(guard, "missing food does not cancel the active guard order");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void markedPersonalBreadIsNotARecoveryRation(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1);
        ItemStack bread = f.body.pack().getItem(0);
        PersonalItems.mark(f.body, bread, "personal reserve");
        ResidentCommands.Order guard = f.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                "the guard is stable before checking its marked bread"))
            .thenExecute(() -> { f.exhaustTo(STARTING_FOOD); f.body.mob().setHealth(10.0F); })
            .thenIdle(130)
            .thenExecute(() -> {
                helper.assertTrue(f.recoveryStatus().endsWith(".missing_food"),
                    "marked personal bread is excluded from recovery rations");
                helper.assertFalse(eating(f.body), "the runner does not consume marked bread");
                helper.assertValueEqual(f.body.pack().count(Items.BREAD), 1,
                    "the protected bread remains physically untouched");
                f.assertCurrent(guard, "marked food does not release the guard");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "recovery_game_rules", timeoutTicks = 220)
    public static void disablingNaturalRegenerationCannotStartRecoveryOrHealForFree(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1);
        helper.getLevel().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false,
            helper.getLevel().getServer());
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.living.foodLevelOf(f.body.id()).isPresent(),
                "the living service has observed the resident before the rule check"))
            .thenExecute(() -> { f.exhaustTo(STARTING_FOOD); f.body.mob().setHealth(10.0F); })
            .thenIdle(130)
            .thenExecute(() -> {
                helper.assertTrue(f.recoveryStatus().endsWith(".disabled"),
                    "the status explains that the world has disabled natural regeneration");
                helper.assertFalse(eating(f.body), "the disabled rule suppresses recovery eating");
                helper.assertValueEqual(f.body.pack().count(Items.BREAD), 1,
                    "disabled natural regeneration leaves the real ration untouched");
                helper.assertValueEqual(f.body.mob().getHealth(), 10.0F,
                    "recovery does not grant a free heal when the gamerule is off");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 360)
    public static void replacingGuardDuringChewingInterruptsWithoutConsumingFood(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1);
        ResidentCommands.Order guard = f.guard();
        ResidentCommands.Order[] move = new ResidentCommands.Order[1];
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                "the guard reaches its slot before recovery"))
            .thenExecute(() -> { f.exhaustTo(STARTING_FOOD); f.body.mob().setHealth(10.0F); })
            .thenIdle(120)
            .thenWaitUntil(() -> helper.assertTrue(eating(f.body),
                "the guard begins its finite 32-tick recovery meal"))
            .thenExecute(() -> {
                helper.assertValueEqual(f.body.pack().count(Items.BREAD), 1,
                    "the meal has not consumed food while its 32-tick workload is underway");
                move[0] = f.commands.move(f.body, f.at(12, 1, 2));
            })
            .thenIdle(12)
            .thenExecute(() -> {
                f.assertCurrent(move[0], "the replacement move becomes the one current order");
                helper.assertTrue(f.body.pack().count(Items.BREAD) == 1,
                    "interrupting the meal before its finish consumes no bread");
                helper.assertFalse(eating(f.body), "the interrupted meal no longer owns the runner");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 420)
    public static void nearbyThreatAndRepeatedHitsKeepRecoveryInItsQuietPeriod(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1);
        ResidentCommands.Order guard = f.guard();
        Zombie threat = new Zombie(f.level);
        BlockPos threatAt = f.at(13, 1, 2).block(f.level);
        threat.moveTo(threatAt.getX() + .5, threatAt.getY(), threatAt.getZ() + .5);
        threat.setNoAi(true);
        f.level.addFreshEntity(threat);
        f.enemies.add(threat);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                "the guard is stable before combat readiness is checked"))
            .thenExecute(() -> { f.exhaustTo(STARTING_FOOD); f.body.mob().setHealth(10.0F); })
            .thenIdle(130)
            .thenExecute(() -> {
                helper.assertTrue(f.recoveryStatus().endsWith(".cooldown"),
                    "a nearby hostile keeps the recovery status in its combat cooldown");
                helper.assertFalse(eating(f.body), "a hostile in sight prevents the recovery runner from eating");
                threat.discard();
                f.repeatedHits = true;
            })
            .thenIdle(120)
            .thenExecute(() -> {
                helper.assertTrue(f.recoveryStatus().endsWith(".cooldown"),
                    "repeated fresh hits keep restarting the quiet period after the hostile leaves");
                helper.assertFalse(eating(f.body), "recovery waits while the resident continues taking hits");
                helper.assertValueEqual(f.body.pack().count(Items.BREAD), 1,
                    "combat delay leaves the real bread untouched");
                f.assertCurrent(guard, "combat cooldown preserves the stable guard order");
            })
            .thenSucceed();
    }

    private static boolean eating(Body body) {
        return body.doing().filter(doing -> doing.toward().orElse(doing.what()).equals(LivingContent.EATING)).isPresent();
    }

    private static final class Fixture implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final Body body;
        final ResidentCommands commands;
        final LivingPresence living;
        final LaborFixture simulation;
        final boolean regenerationBefore;
        final List<Zombie> enemies = new ArrayList<>();
        boolean repeatedHits;
        private boolean closed;

        Fixture(GameTestHelper helper, int bread) {
            this.helper = helper;
            level = helper.getLevel();
            regenerationBefore = level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
            colony = Colonies.mint(level.getServer());
            body = PersonBody.RESIDENT.get().create(level);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 12; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            BlockPos home = helper.absolutePos(new BlockPos(2, 1, 2));
            body.mob().moveTo(home.getX() + .5, home.getY(), home.getZ() + .5);
            if (bread > 0) { body.pack().setItem(0, new ItemStack(Items.BREAD, bread)); }
            setBreadRation();
            level.addFreshEntity(body.mob());
            colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
            body.joinColony(colony.id());
            commands = ResidentCommands.of(colony);
            living = colony.service(LivingContent.ID, LivingPresence.class).orElseThrow();
            simulation = new LaborFixture(colony, level);
            helper.testInfo.addListener(this);
            living.refresh(level.getServer());
            helper.onEachTick(() -> living.tick(level.getServer()));
            helper.onEachTick(simulation::tick);
            helper.onEachTick(() -> {
                if (repeatedHits && level.getGameTime() % 20 == 0 && body.mob().isAlive()) {
                    body.mob().hurt(level.damageSources().magic(), 0.1F);
                }
            });
        }

        private void setBreadRation() {
            var ration = new ColonySettings.Value.Items(List.of(
                ItemFilter.item(ResourceLocation.withDefaultNamespace("bread"))));
            ((ColonyFront) Fronts.of(colony)).setSetting(LivingContent.ID, LivingContent.FOOD.key(), ration);
        }

        ResidentCommands.Order guard() {
            return commands.guard(body, at(2, 1, 2), new Vec3(1, 0, 0)).deliberateGuard();
        }

        WorldPos at(int x, int y, int z) {
            return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z)));
        }

        String recoveryStatus() { return living.recoveryStatus(body.id()); }

        int foodLevel() {
            var level = living.foodLevelOf(body.id());
            if (level.isEmpty()) {
                helper.assertTrue(false, "the living tick initializes the resident's belly");
                return -1;
            }
            return level.get();
        }

        void assertCurrent(ResidentCommands.Order expected, String message) {
            var current = commands.current(body.id());
            helper.assertTrue(current.isPresent() && current.get() == expected, message);
        }

        void exhaustTo(int foodLevel) {
            var observed = living.foodLevelOf(body.id());
            if (observed.isEmpty()) {
                helper.assertTrue(false, "the living tick initializes a full belly before exhaustion");
                return;
            }
            int current = observed.get();
            if (current < foodLevel || current != Hunger.MAX_FOOD_LEVEL) {
                helper.assertTrue(false, "the test begins from an initialized full belly");
                return;
            }
            // The default belly has five saturation points. Twenty exhaustion clears those first;
            // twelve more remove exactly three food points through the normal event path.
            double exhaustion = 5 * 4 + (current - foodLevel) * 4;
            WorkExertion.performed(body.mob(), exhaustion / Hunger.WORK_EXHAUSTION_PER_UNIT, 0, 0);
        }

        private void close() {
            if (closed) { return; }
            closed = true;
            simulation.close();
            level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(regenerationBefore, level.getServer());
            enemies.forEach(Entity::discard);
            body.mob().discard();
            colony.raze();
        }

        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
