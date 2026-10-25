package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkYield;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.patrol.StrikeNode;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicFightStrikeYieldGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(EpicFightStrikeYieldGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void completedStrikeCreditsCommitXpOnlyOnceAcrossRelease(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        StrikeNode node = new StrikeNode(scene.level, scene.zombie, Stances.WHEREVER);
        Workload.Continuous workload = (Workload.Continuous)node.spec().workload();
        AtomicInteger yields = new AtomicInteger();
        AtomicInteger activeTicks = new AtomicInteger();
        Consumer<WorkYield> listener = event -> {
            if (event.body() == scene.resident && event.node() == node) { yields.incrementAndGet(); }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            scene.seedLaborRemainder();
            boolean initiallyHeld = workload.holds(scene.level, scene.worker);
            helper.assertTrue(initiallyHeld, "the real guard strike begins through its continuous labor workload");
            if (initiallyHeld) { activeTicks.incrementAndGet(); }
            helper.assertTrue(scene.resident.hasWeaponSession(), "the strike owns the actual Epic Fight weapon session");
            advanceUntilDead(helper, scene, workload, activeTicks, 0, () -> {
                helper.assertFalse(workload.holds(scene.level, scene.worker), "the next labor advance observes the completed strike");
                Outcome.Done outcome = (Outcome.Done)node.commit(scene.level, scene.worker);
                Xp awarded = outcome.xp().orElseThrow(() -> new AssertionError("a normal completed strike returns its patrol XP outcome"));
                helper.assertValueEqual(awarded.amount(), 2, "the commit carries its existing XP outcome descriptor");
                Bodies.worked(scene.body, awarded.vocation(), activeTicks.get(), scene.level.getRandom());
                helper.assertTrue(scene.xp() > 0, "BodyRunner-style settlement credits the accumulated active labor ticks after the seeded remainder");
                WorkYield.gave(scene.resident, node, List.of());
                int credited = scene.xp();
                node.released(scene.level, scene.worker, Ending.DONE);
                node.released(scene.level, scene.worker, Ending.DONE);
                helper.assertValueEqual(scene.xp(), credited, "release after normal commit does not add the same labor a second time");
                helper.assertValueEqual(yields.get(), 1, "normal completion emits one yield and release does not duplicate it");
                helper.assertFalse(scene.resident.hasWeaponSession(), "commit release returns the borrowed weapon");
                scene.close();
                NeoForge.EVENT_BUS.unregister(listener);
                helper.succeed();
            });
        } catch (Throwable failure) {
            scene.close();
            NeoForge.EVENT_BUS.unregister(listener);
            throw failure;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void asynchronousKillReleasedBeforeNextAdvanceStillCreditsLaborOnce(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        StrikeNode node = new StrikeNode(scene.level, scene.zombie, Stances.WHEREVER);
        Workload.Continuous workload = (Workload.Continuous)node.spec().workload();
        AtomicInteger yields = new AtomicInteger();
        Consumer<WorkYield> listener = event -> {
            if (event.body() == scene.resident && event.node() == node) { yields.incrementAndGet(); }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            scene.seedLaborRemainder();
            helper.assertTrue(workload.holds(scene.level, scene.worker), "the real guard strike begins through its continuous labor workload");
            advanceUntilDead(helper, scene, workload, new AtomicInteger(), 0, () -> {
                node.released(scene.level, scene.worker, Ending.DROPPED);
                int credited = scene.xp();
                helper.assertTrue(credited > 0, "the asynchronous Epic Fight kill credits active labor ticks on release");
                helper.assertValueEqual(yields.get(), 1, "the interrupted but successful strike emits one yield event");
                node.released(scene.level, scene.worker, Ending.DROPPED);
                helper.assertValueEqual(scene.xp(), credited, "repeated release cannot credit the same kill twice");
                helper.assertValueEqual(yields.get(), 1, "repeated release cannot duplicate the yield event");
                helper.assertFalse(scene.resident.hasWeaponSession(), "release ends the real session and returns the sword");
                scene.close();
                NeoForge.EVENT_BUS.unregister(listener);
                helper.succeed();
            });
        } catch (Throwable failure) {
            scene.close();
            NeoForge.EVENT_BUS.unregister(listener);
            throw failure;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void externallyKilledTargetCannotEarnStrikeYieldOrExperience(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        StrikeNode node = new StrikeNode(scene.level, scene.zombie, Stances.WHEREVER);
        Workload.Continuous workload = (Workload.Continuous)node.spec().workload();
        AtomicInteger yields = new AtomicInteger();
        Consumer<WorkYield> listener = event -> {
            if (event.body() == scene.resident && event.node() == node) { yields.incrementAndGet(); }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            scene.seedLaborRemainder();
            helper.assertTrue(workload.holds(scene.level, scene.worker), "the resident starts its strike before an external actor intervenes");
            helper.assertTrue(scene.zombie.hurt(scene.level.damageSources().magic(), 100.0F), "the external source kills the still-pending target");
            helper.assertTrue(scene.zombie.getLastDamageSource() == null
                || scene.zombie.getLastDamageSource().getEntity() != scene.resident,
                "the fatal damage source is not the patrolling resident");
            node.released(scene.level, scene.worker, Ending.DROPPED);
            node.released(scene.level, scene.worker, Ending.DROPPED);
            helper.assertValueEqual(scene.xp(), 0, "external kills earn no patrol XP");
            helper.assertValueEqual(yields.get(), 0, "external kills emit no successful-strike yield");
            helper.assertFalse(scene.resident.hasWeaponSession(), "external cancellation still releases the borrowed weapon");
        } finally {
            scene.close();
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    private static void advanceUntilDead(GameTestHelper helper, Scene scene, Workload.Continuous workload,
            AtomicInteger activeTicks, int elapsed, Runnable defeated) {
        if (elapsed > 100) { throw new AssertionError("the Epic Fight strike did not kill its one-health zombie within 100 world ticks"); }
        helper.runAfterDelay(1, () -> {
            if (!scene.zombie.isAlive()) {
                defeated.run();
                return;
            }
            boolean holding = workload.holds(scene.level, scene.worker);
            if (holding) { activeTicks.incrementAndGet(); }
            if (!scene.zombie.isAlive()) {
                defeated.run();
                return;
            }
            if (!holding) { throw new AssertionError("the first Epic Fight strike ended without killing its intended target"); }
            advanceUntilDead(helper, scene, workload, activeTicks, elapsed + 1, defeated);
        });
    }

    private static final class Scene implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity resident;
        final io.github.izakyl.folkways.core.api.resident.body.Body body;
        final Zombie zombie;
        final Worker worker;

        Scene(GameTestHelper helper) {
            level = helper.getLevel();
            for (int x = 0; x < 6; x++) {
                for (int z = 0; z < 3; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            colony = Colonies.mint(level.getServer());
            resident = PersonBody.RESIDENT.get().create(level);
            resident.setNoAi(true);
            BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
            resident.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
            resident.pack().insert(new ItemStack(Items.IRON_SWORD));
            helper.assertTrue(level.addFreshEntity(resident), "the resident is actually loaded before colony admission");
            colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            body = Bodies.of(resident).orElseThrow();
            zombie = EntityType.ZOMBIE.create(level);
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 10));
            zombie.setHealth(1.0F);
            zombie.moveTo(origin.getX() + 2.0, origin.getY(), origin.getZ() + .5);
            level.addFreshEntity(zombie);
            worker = new Worker() {
                public io.github.izakyl.folkways.core.api.resident.Resident resident() { return body.resident(); }
                public Mob body() { return resident; }
                public Container pack() { return resident.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { resident.spawnAtLocation(stack); }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        int xp() { return body.perks().xp(PatrolContent.VOCATION); }

        void seedLaborRemainder() {
            Bodies.worked(body, Vocations.required(PatrolContent.VOCATION), 19, level.getRandom());
        }

        public void close() {
            resident.discard();
            zombie.discard();
            colony.raze();
        }
    }
}
