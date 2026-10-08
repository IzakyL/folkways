package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.travel.graph.WayGraph;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        colony.hold(WaresContent.STORE, new Held.Block(WorldPos.of(level, cell)));
        ((Container) level.getBlockEntity(cell)).setItem(0, new ItemStack(Items.OAK_LOG, 17));
        body.moveTo(Vec3.atBottomCenterOf(cell.east()));
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Held.Entity.of(body));
        body.pack().insert(new ItemStack(Items.OAK_PLANKS, 7));
        level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(cell.east().below(), Blocks.STONE.defaultBlockState());
        var drop = new ItemEntity(level, cell.getX() + 1.5, cell.getY(), cell.getZ() + 0.5,
            new ItemStack(Items.STICK, 3));
        level.addFreshEntity(drop);
        AtomicBoolean aboard = new AtomicBoolean();
        AtomicBoolean tracking = new AtomicBoolean();
        UUID structure = UUID.randomUUID();
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return structure; }
            public Level level() { return level; }
            public BlockPos storageOrigin() { return cell; }
            public Vec3 toWorld(Vec3 local) { return local.add(Vec3.atLowerCornerOf(cell)); }
            public Vec3 toLocal(Vec3 world) { return world.subtract(Vec3.atLowerCornerOf(cell)); }
        };
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
                oldSurvey.answer(level, data, Map.of(body.id(), body), ignored -> { });
                WorldPos old = WorldPos.of(level, cell);
                helper.assertTrue(oldSurvey.known(WayGraph.UNBUILT, List.of()).world().stock().holds(Stash.at(old)),
                    "the old store reading must exist before relocation");
                AtomicInteger accepted = new AtomicInteger();
                Node node = node(old, accepted);
                colony.submit(OWNER, level.dimension(), Grown.of(node), (any, how) -> { });
                labor.run(level, queued::add);
                helper.assertTrue(!queued.isEmpty(), "an old-world plan must be in flight");
                var runnersField = ColonyLabor.class.getDeclaredField("runners");
                runnersField.setAccessible(true);
                BodyRunner runner = ((Runners) runnersField.get(labor)).of(body.id());
                Node active = node(old, new AtomicInteger());
                labor.personal().ready(active, level);
                runner.offer(labor, level, body, new Urge(OWNER, 1, false, () -> active), active);
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
                while (!queued.isEmpty()) queued.removeFirst().run();
                retired.run(level, queued::add);
                helper.assertTrue(accepted.get() == 0 && queued.isEmpty(),
                    "the old solve must never apply or restart after relocation");
                Survey fresh = survey(labor);
                helper.assertTrue(fresh.known(WayGraph.UNBUILT, List.of()).hands().isEmpty()
                        && fresh.known(WayGraph.UNBUILT, List.of()).world().stock().stashes().isEmpty(),
                    "old resident and store readings must not cross the migration boundary");
                labor.run(level, Runnable::run);
                helper.assertTrue(reopened.get() == 1,
                    "work submitted while reopening must enter the new runtime exactly once");
                Salvage newSalvage = (Salvage) salvageField.get(labor);
                helper.assertTrue(newSalvage.drops().contains(drop.getUUID())
                        && newSalvage.workshop().orElseThrow().site().where().equals(WorldSpaces.at(drop))
                        && drop.getItem().getCount() == 3,
                    "tracked loose goods must be reoffered at their new address without changing the stack");
                tracking.set(intoFrame);
                fresh.asked(Asks.NONE);
                helper.assertTrue(fresh.answer(level, data, Map.of(body.id(), body), ignored -> { }),
                    "a delayed entity realm change must refresh the resident even with no pending asks");
                fresh.asked(new Asks(true, Set.of(), false, Set.of(), Long.MAX_VALUE));
                fresh.answer(level, data, Map.of(body.id(), body), ignored -> { });
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
            while (!queued.isEmpty()) queued.removeFirst().run();
            uninstall.run();
            body.discard();
            drop.discard();
            colony.raze();
        }
        helper.succeed();
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
            public Optional<io.github.izakyl.folkways.core.api.terms.RefusalKind> planned(ServerLevel level) {
                accepted.incrementAndGet();
                return Optional.empty();
            }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }
}
