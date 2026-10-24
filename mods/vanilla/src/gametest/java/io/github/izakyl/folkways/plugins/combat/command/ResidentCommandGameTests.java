package io.github.izakyl.folkways.plugins.combat.command;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentCommandGameTests {
    private static final ResourceLocation WORK = ResourceLocation.fromNamespaceAndPath("folkways_test", "command_work");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ResidentCommandGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 800)
    public static void ordersInterruptWorkKeepCargoAndToolsAndResumeTheLatestPlan(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        body.pack().insert(new ItemStack(Items.IRON_INGOT, 8));
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(7);
        body.pack().insert(axe);
        Job original = new Job(f.at(2, 1, 2), body);
        Job replacement = new Job(f.at(2, 1, 2), body);
        AtomicReference<ResidentCommands.Order> order = new AtomicReference<>();
        f.submit(original);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(original.working.get(), "the real runner started the original job"))
            .thenExecute(() -> order.set(f.commands.hold(body, f.at(12, 1, 2))))
            .thenWaitUntil(() -> helper.assertTrue(order.get().status() == ResidentCommands.Status.HOLDING,
                "the order reaches its destination through Core travel"))
            .thenExecute(() -> {
                helper.assertValueEqual(original.released.get(), 1, "work is released exactly once on takeover");
                f.assertCargo(helper, body);
                f.colony.withdraw(WORK, original.id());
                f.submit(replacement);
            })
            .thenIdle(15)
            .thenExecute(() -> {
                helper.assertFalse(replacement.working.get(), "labour cannot navigate while the command holds the body");
                order.get().close();
            })
            .thenWaitUntil(() -> helper.assertTrue(replacement.working.get(), "release resumes the latest work plan"))
            .thenExecute(() -> {
                helper.assertValueEqual(original.committed.get(), 0, "the superseded job is never replayed");
                f.assertCargo(helper, body);
                replacement.finish.set(true);
            })
            .thenWaitUntil(() -> helper.assertValueEqual(replacement.committed.get(), 1, "replacement work commits once"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 400)
    public static void moveFinishesAndRetreatHoldsUntilReleased(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order move = f.commands.move(body, f.at(8, 1, 2));
        AtomicReference<ResidentCommands.Order> retreat = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(move.status() == ResidentCommands.Status.ARRIVED, "move completes"))
            .thenExecute(() -> {
                helper.assertTrue(f.commands.current(body.id()).isEmpty(), "finished movement releases control");
                retreat.set(f.commands.retreat(body, f.at(2, 1, 2)));
            })
            .thenWaitUntil(() -> helper.assertTrue(retreat.get().status() == ResidentCommands.Status.HOLDING,
                "retreat reaches and holds its safe destination"))
            .thenIdle(20)
            .thenExecute(() -> {
                helper.assertTrue(body.mob().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(
                    f.at(2, 1, 2).block(f.level))) < 2, "the body stays at the retreat destination");
                body.mob().setDeltaMovement(1.2, .3, 0);
            })
            .thenIdle(10)
            .thenWaitUntil(() -> helper.assertTrue(io.github.izakyl.folkways.core.api.terms.Reach.standingAt(
                    body.mob().position(), f.at(2, 1, 2).block(f.level)),
                "a held resident displaced by an impulse walks back to its stance"))
            .thenExecute(() -> {
                retreat.get().close();
                helper.assertTrue(f.commands.current(body.id()).isEmpty(), "release relinquishes the command");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 260)
    public static void replacingATripCannotBeCancelledByItsOldHandle(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order old = f.commands.hold(body, f.at(18, 1, 2));
        AtomicReference<ResidentCommands.Order> next = new AtomicReference<>();
        helper.startSequence().thenIdle(8)
            .thenExecute(() -> {
                next.set(f.commands.retreat(body, f.at(2, 1, 2)));
                old.close();
                helper.assertTrue(f.commands.current(body.id()).orElseThrow() == next.get(),
                    "the old release token cannot cancel its replacement");
            })
            .thenWaitUntil(() -> helper.assertTrue(next.get().status() == ResidentCommands.Status.HOLDING,
                "a replacement interrupts the trip and takes effect"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void unloadAndNbtReloadKeepInventoryWithoutRestoringControl(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        body.pack().insert(new ItemStack(Items.IRON_INGOT, 8));
        ResidentCommands.Order order = f.commands.hold(body, f.at(2, 1, 2));
        helper.runAfterDelay(10, () -> {
            CompoundTag tag = new CompoundTag();
            body.mob().save(tag);
            body.mob().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertTrue(f.commands.current(body.id()).isEmpty(), "unloading immediately releases control");
            helper.assertTrue(order.status() == ResidentCommands.Status.RELEASED, "the handle observes release");
            Body loaded = (Body) EntityType.loadEntityRecursive(tag, f.level, entity -> entity);
            f.bodies.add(loaded);
            f.level.addFreshEntity(loaded.mob());
            helper.assertValueEqual(loaded.pack().count(Items.IRON_INGOT), 8, "real cargo survives reload exactly once");
            helper.assertTrue(f.commands.current(loaded.id()).isEmpty(), "temporary orders are absent after reload");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 120)
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
            .thenWaitUntil(() -> helper.assertTrue(dead.status() == ResidentCommands.Status.RELEASED,
                "death releases the command without waiting for a trip timeout"))
            .thenExecute(() -> {
                var drops = f.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    dying.mob().getBoundingBox().inflate(2));
                helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.IRON_INGOT))
                    .mapToInt(item -> item.getItem().getCount()).sum(), 8, "death drops each material exactly once");
                helper.assertValueEqual(drops.stream().filter(item -> item.getItem().is(Items.IRON_AXE))
                    .mapToInt(item -> item.getItem().getCount()).sum(), 1, "the presentation copy creates no duplicate tool");
                f.colony.raze();
                helper.assertTrue(closing.status() == ResidentCommands.Status.RELEASED, "colony close releases holds");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void invalidCommandsDoNotReplaceAnExistingOrder(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(new BlockPos(2, 1, 2));
        ResidentCommands.Order order = f.commands.hold(body, f.at(2, 1, 2));
        try {
            f.commands.move(body, f.at(1000, 1, 2));
            helper.fail("unloaded or distant movement must be rejected");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(f.commands.current(body.id()).orElseThrow() == order,
                "reject before replacing the existing command");
        }
        helper.succeed();
    }

    private static final class Job implements Node {
        final AtomicBoolean working = new AtomicBoolean();
        final AtomicBoolean finish = new AtomicBoolean();
        final AtomicInteger released = new AtomicInteger();
        final AtomicInteger committed = new AtomicInteger();
        final NodeSpec spec;
        final UUID worker;

        Job(WorldPos at, Body body) {
            worker = body.id();
            Workload.Continuous load = (level, who) -> { working.set(true); return !finish.get(); };
            spec = NodeSpec.of(UUID.randomUUID(), WORK, new WorkSite.AtBlock(at), Stances.at(at), load)
                .tools(new ToolUse(new ToolNeed.Tagged(ItemTags.AXES), 0)).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<UUID> worker() { return Optional.of(worker); }
        public Outcome commit(ServerLevel level, Worker who) { committed.incrementAndGet(); return Outcome.done(); }
        public void released(ServerLevel level, Worker who, Ending how) { released.incrementAndGet(); }
    }

    private static final class Fixture implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final LaborFixture simulation;
        final List<Body> bodies = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony);
            simulation = new LaborFixture(colony, level);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 12; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            helper.testInfo.addListener(this);
            helper.onEachTick(simulation::tick);
        }
        WorldPos at(int x, int y, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z))); }
        Body resident(BlockPos at) {
            Body body = PersonBody.RESIDENT.get().create(level);
            BlockPos absolute = helper.absolutePos(at);
            body.mob().moveTo(absolute.getX() + .5, absolute.getY(), absolute.getZ() + .5);
            colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
            body.joinColony(colony.id());
            bodies.add(body);
            level.addFreshEntity(body.mob());
            return body;
        }
        void submit(Node job) { colony.submit(WORK, level.dimension(), new Grown(List.of(job), List.of()), (id, how) -> { }); }
        void assertCargo(GameTestHelper helper, Body body) {
            helper.assertValueEqual(body.pack().count(Items.IRON_INGOT), 8, "cargo is retained through takeover");
            helper.assertValueEqual(body.pack().count(Items.IRON_AXE), 1, "the borrowed tool has one real owner");
            helper.assertTrue(body.pack().contents().stream().filter(stack -> stack.is(Items.IRON_AXE))
                .allMatch(stack -> stack.getDamageValue() == 7), "the tool keeps its component state");
        }
        void close() { simulation.close(); bodies.forEach(body -> body.mob().discard()); colony.raze(); }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
