package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UnitGroupGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(UnitGroupGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 1000)
    public static void fourResidentsPassAOneBlockOpeningAndRestoreTheirLine(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people(4);
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z < 22; z++) {
                if (z != 10) { helper.setBlock(new BlockPos(9, y, z), Blocks.STONE); }
            }
        }
        UnitGroup group = scene.groups.create(people);
        group.move(scene.line(18));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING,
                "all four members must traverse the opening and restore the requested line; phase=" + group.phase()))
            .thenExecute(() -> {
                scene.assertLine(helper, group, people);
                group.release();
                helper.assertTrue(people.stream().allMatch(body -> scene.commands.current(body.id()).isEmpty()),
                    "group release relinquishes every command handle");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void actualMembershipIsExclusiveAndPersistsWithoutRestoringOrders(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people(4);
        UnitGroup first = scene.groups.create(people);
        UnitGroup second = scene.groups.create(List.of(people.getFirst()));
        helper.assertValueEqual(first.members().size(), 3, "transfer removes previous actual membership");
        helper.assertTrue(scene.groups.ofResident(people.getFirst().id()).orElseThrow() == second,
            "one resident has exactly one actual group");
        UnitGroups loaded = new UnitGroups(scene.colony, scene.level.getServer());
        helper.assertTrue(loaded.group(first.id()).orElseThrow().members().equals(first.members()),
            "group identity and membership are restored from colony NBT");
        helper.assertTrue(loaded.ofResident(people.getFirst().id()).orElseThrow().id().equals(second.id()),
            "exclusive membership survives reload");
        helper.assertTrue(loaded.group(first.id()).orElseThrow().phase() == UnitGroup.Phase.IDLE,
            "transient deployment control is not restored from a save");
        loaded.clear();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 800)
    public static void deathAndUnloadReformTheLineAndReloadRejoinsTheMember(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people(4);
        UnitGroup group = scene.groups.create(people);
        group.hold(scene.line(12));
        Body dying = people.get(0);
        Body away = people.get(1);
        AtomicReference<Body> returned = new AtomicReference<>();
        CompoundTag saved = new CompoundTag();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING, "initial formation holds"))
            .thenExecute(() -> {
                away.mob().save(saved);
                away.mob().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                dying.mob().kill();
            })
            .thenWaitUntil(() -> {
                helper.assertValueEqual(group.members().size(), 3, "the dead member leaves actual membership");
                helper.assertTrue(group.missing().equals(Set.of(away.id())), "an unloaded member remains missing");
                helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING && group.destinations().size() == 2,
                    "the two present members reform without waiting forever");
            })
            .thenExecute(() -> {
                Body body = (Body) EntityType.loadEntityRecursive(saved, scene.level, entity -> entity);
                returned.set(body);
                scene.bodies.add(body);
                scene.level.addFreshEntity(body.mob());
            })
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING
                    && group.destinations().size() == 3 && group.missing().isEmpty(),
                "the returned member receives a fresh slot and the group reforms"))
            .thenExecute(() -> scene.assertLine(helper, group, List.of(returned.get(), people.get(2), people.get(3))))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 700)
    public static void anIndividualOrderDetachesItsMemberWithoutBeingOverwritten(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people(4);
        UnitGroup group = scene.groups.create(people);
        group.hold(scene.line(12));
        Body individual = people.getFirst();
        AtomicReference<ResidentCommands.Order> order = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING, "formation holds"))
            .thenExecute(() -> order.set(scene.commands.retreat(individual, scene.at(2, 1, 4))))
            .thenWaitUntil(() -> {
                helper.assertFalse(group.members().contains(individual.id()), "individual control leaves the actual group");
                helper.assertTrue(order.get().status() == ResidentCommands.Status.HOLDING,
                    "reforming the group cannot cancel the newer individual command");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 180)
    public static void invalidDeploymentDoesNotPartiallyReplaceAFormation(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people(4);
        UnitGroup group = scene.groups.create(people);
        group.hold(scene.line(12));
        Map<UUID, WorldPos> original = group.destinations();
        helper.setBlock(new BlockPos(18, 1, 7), Blocks.STONE);
        try {
            group.move(scene.line(18));
            helper.fail("a blocked formation slot must be rejected before issuing any command");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(group.destinations().equals(original), "all old destinations remain intact on rejection");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 2000)
    public static void unreachableDeploymentReleasesControlAndCanBeRetriedAfterOpeningAPath(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people(4);
        for (int y = 1; y <= 4; y++) {
            for (int x = 10; x <= 20; x++) {
                helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 15), Blocks.STONE);
            }
            for (int z = 5; z <= 15; z++) {
                helper.setBlock(new BlockPos(10, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(20, y, z), Blocks.STONE);
            }
        }
        UnitGroup group = scene.groups.create(people);
        Deployment intent = scene.line(18);
        group.move(intent);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.FAILED,
                "an unreachable formation reports a bounded failure; phase=" + group.phase()))
            .thenExecute(() -> {
                helper.assertTrue(people.stream().allMatch(body -> scene.commands.current(body.id()).isEmpty()),
                    "failure releases every controlled member");
                for (int y = 1; y <= 3; y++) { helper.setBlock(new BlockPos(10, y, 10), Blocks.AIR); }
                group.move(intent);
            })
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING,
                "a fresh deployment recovers after the path opens; phase=" + group.phase()))
            .thenExecute(() -> scene.assertLine(helper, group, people))
            .thenSucceed();
    }

    private static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final UnitGroups groups;
        final LaborFixture simulation;
        final List<Body> bodies = new ArrayList<>();

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony);
            groups = UnitGroups.of(colony);
            simulation = new LaborFixture(colony, level);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 22; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            helper.onEachTick(simulation::tick);
            helper.testInfo.addListener(this);
        }
        WorldPos at(int x, int y, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z))); }
        Deployment line(int x) { return new Deployment(at(x, 1, 7), at(x, 1, 13), new Vec3(1, 0, 0), new Deployment.Formation(2)); }
        List<Body> people(int count) {
            List<Body> people = new ArrayList<>();
            for (int n = 0; n < count; n++) {
                Body body = PersonBody.RESIDENT.get().create(level);
                BlockPos cell = at(2, 1, 7 + n * 2).block(level);
                body.mob().moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
                body.joinColony(colony.id());
                bodies.add(body);
                people.add(body);
                level.addFreshEntity(body.mob());
            }
            return List.copyOf(people);
        }
        void assertLine(GameTestHelper helper, UnitGroup group, List<Body> people) {
            helper.assertValueEqual(new HashSet<>(group.destinations().values()).size(), people.size(), "slots never overlap");
            for (Body body : people) {
                helper.assertTrue(Reach.standingAt(body.mob().position(), group.destinations().get(body.id()).block(level)),
                    "each member physically stands in its assigned slot");
            }
        }
        void close() { simulation.close(); bodies.forEach(body -> body.mob().discard()); colony.raze(); }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
