package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Asks;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.testing.TestFrame;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraph;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RelocationGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "relocation");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RelocationGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void assemblyAndReturnRetireOldReadingsAndPlans(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        var body = PersonBody.RESIDENT.get().create(level);
        var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.CHEST.defaultBlockState());
        colony.hold(WaresContent.STORE, new Claimed.Block(WorldPos.of(level, cell)));
        ((Container) level.getBlockEntity(cell)).setItem(0, new ItemStack(Items.OAK_LOG, 17));
        body.moveTo(Vec3.atBottomCenterOf(cell.east()));
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body));
        body.pack().insert(new ItemStack(Items.OAK_PLANKS, 7));
        level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(cell.east().below(), Blocks.STONE.defaultBlockState());
        var drop = new ItemEntity(level, cell.getX() + 1.5, cell.getY(), cell.getZ() + 0.5,
            new ItemStack(Items.STICK, 3));
        level.addFreshEntity(drop);
        AtomicBoolean aboard = new AtomicBoolean();
        AtomicBoolean tracking = new AtomicBoolean();
        TestFrame frame = TestFrame.stored(level, cell);
        UUID structure = frame.id();
        Runnable uninstall = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<? extends WorldSpaces.Frame> frame(Level in, UUID id) {
                return in == level && id.equals(structure) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<? extends WorldSpaces.Frame> containing(Level in, BlockPos at) {
                return aboard.get() && in == level && at.equals(cell)
                    ? Optional.of(frame) : Optional.empty();
            }
            public Optional<? extends WorldSpaces.Frame> aboard(Entity entity) {
                return (entity == body && tracking.get() || entity == drop && aboard.get())
                    ? Optional.of(frame) : Optional.empty();
            }
        });
        ColonyLabor labor = new ColonyLabor(data, level.dimension());
        ColonyLabor other = new ColonyLabor(new ColonyData(), level.dimension());
        List<Runnable> queued = new ArrayList<>();
        try {
            for (boolean intoFrame : List.of(true, false)) {
                Survey oldSurvey = survey(labor);
                var salvageField = ColonyLabor.class.getDeclaredField("salvage");
                salvageField.setAccessible(true);
                Salvage oldSalvage = (Salvage) salvageField.get(labor);
                helper.assertTrue(oldSalvage.dropped(drop).isPresent(), "the loose item must be tracked before migration");
                oldSurvey.asked(new Asks(true, Set.of(), true, Set.of(), Long.MAX_VALUE));
                answer(oldSurvey, level, data, body);
                WorldPos old = WorldPos.of(level, cell);
                helper.assertTrue(oldSurvey.known(WayGraph.UNBUILT, List.of()).world().stock().holds(Stash.at(old)),
                    "the old store reading must exist before relocation");
                AtomicInteger accepted = new AtomicInteger();
                Node node = node(old, accepted);
                colony.submit(OWNER, level.dimension(), Grown.of(node), (any, how) -> { });
                labor.run(level, new Lanes(queued::add, queued::add));
                helper.assertTrue(!queued.isEmpty(), "an old-world plan must be in flight");
                var runnersField = ColonyLabor.class.getDeclaredField("runners");
                runnersField.setAccessible(true);
                BodyRunner runner = ((Runners) runnersField.get(labor)).of(body.id());
                Node active = node(old, new AtomicInteger());
                labor.execution().personal().ready(active, level);
                runner.offer(level, body, new Urge(OWNER, 1, false, () -> active), active);
                helper.assertTrue(body.doing().isPresent(), "the resident must have active work to retire");

                aboard.set(intoFrame);
                WorldPos moved = WorldPos.of(level, cell);
                data.relocate(level.getServer(), Map.of(old, moved));
                helper.assertTrue(!runner.executing() && body.doing().isEmpty(),
                    "migration must release active work and its presentation");
                AtomicInteger reopened = new AtomicInteger();
                colony.submit(OWNER, level.dimension(), Grown.of(node(moved, reopened)), (any, how) -> { });
                ColonyLabor retired = labor;
                labor = retired.current();
                helper.assertTrue(labor != retired && other.current() == other,
                    "only the affected colony's labor must reopen");
                while (!queued.isEmpty()) {
                    queued.removeFirst().run();
                }
                retired.run(level, new Lanes(queued::add, queued::add));
                helper.assertTrue(accepted.get() == 0 && queued.isEmpty(),
                    "the old solve must never apply or restart after relocation");
                Survey fresh = survey(labor);
                helper.assertTrue(fresh.known(WayGraph.UNBUILT, List.of()).hands().isEmpty()
                        && fresh.known(WayGraph.UNBUILT, List.of()).world().stock().stashes().isEmpty(),
                    "old resident and store readings must not cross the migration boundary");
                answer(fresh, level, data, body);
                labor.run(level, Lanes.INLINE);
                helper.assertTrue(reopened.get() == 1,
                    "work submitted while reopening must enter the new runtime exactly once");
                Salvage newSalvage = (Salvage) salvageField.get(labor);
                helper.assertTrue(newSalvage.drops().contains(drop.getUUID())
                        && newSalvage.workshop().orElseThrow().site().where().equals(WorldSpaces.at(drop))
                        && drop.getItem().getCount() == 3,
                    "tracked loose goods must be reoffered at their new address without changing the stack");
                tracking.set(intoFrame);
                fresh.asked(Asks.NONE);
                helper.assertTrue(answer(fresh, level, data, body),
                    "a delayed entity realm change must refresh the resident even with no pending asks");
                fresh.asked(new Asks(true, Set.of(), false, Set.of(), Long.MAX_VALUE));
                answer(fresh, level, data, body);
                var known = fresh.known(WayGraph.UNBUILT, List.of());
                helper.assertTrue(known.hands().stream().anyMatch(hand -> hand.id().equals(body.id())
                        && hand.at().equals(WorldSpaces.at(body)))
                        && known.world().stock().holds(Stash.at(moved))
                        && !known.world().stock().holds(Stash.at(old)),
                    "the reopened runtime must read the new resident realm and store address");
                helper.assertTrue(body.pack().count(Items.OAK_PLANKS) == 7
                        && ((Container) level.getBlockEntity(cell)).getItem(0).getCount() == 17
                        && colony.residents().stream().anyMatch(who -> who.id().equals(body.id())),
                    "migration must preserve carried goods, inventory and resident identity");
                helper.assertTrue(moved.realm() instanceof Realm.Frame == intoFrame,
                    "both assembly and return to the world must be covered");
            }
        } finally {
            labor.close();
            other.close();
            while (!queued.isEmpty()) {
                queued.removeFirst().run();
            }
            uninstall.run();
            body.discard();
            drop.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static boolean answer(Survey survey, ServerLevel level, ColonyData data, Body body) {
        boolean changed = survey.answer(level, data, Map.of(body.id(), body), ignored -> { });
        InventoryObservations.work(data.inventory(), level.getServer(), 100);
        return survey.answer(level, data, Map.of(body.id(), body), ignored -> { }) || changed;
    }

    private static Survey survey(ColonyLabor labor) throws ReflectiveOperationException {
        var field = ColonyLabor.class.getDeclaredField("survey");
        field.setAccessible(true);
        return (Survey) field.get(labor);
    }

    private static Node node(WorldPos at, AtomicInteger accepted) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at),
            Stances.WHEREVER, Workload.Once.of(1)).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Optional<io.github.izakyl.folkways.core.resident.api.RefusalKind> planned(ServerLevel level) {
                accepted.incrementAndGet();
                return Optional.empty();
            }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }
}
