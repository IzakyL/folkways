package io.github.izakyl.folkways.plugins.build;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateJointGameTests {

    static Item track() {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "track"));
    }

    private CreateJointGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CreateJointGameTests.class);
    }

    static BlockState track(String shape) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                "create:track[shape=" + shape + "]", false).blockState();
        } catch (Exception unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    static List<ItemStack> of(Item item, int count) {
        List<ItemStack> made = new ArrayList<>();
        for (int left = count; left > 0; left -= 64) {
            made.add(new ItemStack(item, Math.min(64, left)));
        }
        return made;
    }

    static BlockPos base(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(1, 30, 1));
    }

    static void floor(ServerLevel level, BlockPos base, int size) {
        for (int x = -2; x <= size; x++) {
            for (int z = -2; z <= size; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    static int curveEdges(BlockPos base, int reach) {
        int curves = 0;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation location : graph.getNodes()) {
                Vec3 at = location.getLocation().subtract(Vec3.atLowerCornerOf(base));
                if (at.x < -1 || at.z < -1 || at.x > reach || at.z > reach || Math.abs(at.y) > 4) {
                    continue;
                }
                var node = graph.locateNode(location);
                for (var edge : graph.getConnectionsFrom(node).values()) {
                    if (edge.isTurn()) {
                        curves++;
                    }
                }
            }
        }
        return curves;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aTurnCostsWhatCreateChargesAndIsBuiltOnlyWhenPaidInFull(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = base(helper);
        floor(level, base, 10);
        BlockPos from = base;
        BlockPos to = base.offset(8, 0, 8);
        level.setBlock(from, track("xo"), 3);
        level.setBlock(to, track("zo"), 3);
        Joints.Joiner joiner = Joints.joiner();
        var cost = joiner.cost(level, from, to).orElseThrow(() -> new AssertionError("Create lays this turn"));
        int owed = count(cost, track());
        helper.assertTrue(owed > 0 && cost.stream().allMatch(stack -> stack.is(track())), "a turn is paid in track: " + cost);
        var sketch = Joints.sketch(from, track("xo"), to, track("zo"));
        helper.assertTrue(sketch.size() >= 8 && sketch.keySet().stream().allMatch(cell -> cell.getY() == from.getY()
            && cell.getX() >= from.getX() && cell.getX() <= to.getX() && cell.getZ() >= from.getZ()
            && cell.getZ() <= to.getZ()), "an unbuilt turn is sketched between its ends: " + sketch.keySet());

        var short1 = joiner.join(level, from, to, of(track(), owed - 1));
        helper.assertTrue(!short1.joined(), "one track short, nothing is built");
        helper.assertValueEqual(count(short1.left(), track()), owed - 1, "and every track offered comes back");
        helper.assertTrue(!joiner.joined(level, from, to), "the ends stay apart");

        var paid = joiner.join(level, from, to, of(track(), owed + 3));
        helper.assertTrue(paid.joined(), "paid in full, the turn is laid");
        helper.assertValueEqual(count(paid.left(), track()), 3, "Create takes exactly what it asked");
        helper.assertTrue(joiner.joined(level, from, to), "both ends hold the curve");
        helper.assertValueEqual(joiner.cost(level, from, to).orElseThrow(), List.of(), "a laid turn costs nothing more");

        helper.runAfterDelay(10, () -> {
            helper.assertTrue(curveEdges(base, 10) > 0, "the curve is in Create's track graph");
            var refund = joiner.loosen(level, from);
            helper.assertValueEqual(count(refund, track()), owed, "taking the turn up gives back what laying it cost");
            helper.assertTrue(!joiner.joined(level, from, to), "the curve is gone");
            helper.runAfterDelay(10, () -> {
                helper.assertValueEqual(level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(12)).size(), 0,
                    "nothing spills on the ground");
                helper.assertValueEqual(curveEdges(base, 10), 0, "and Create's graph forgets it");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aTurnCreateWouldRefuseHasNoCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = base(helper);
        floor(level, base, 4);
        level.setBlock(base, track("xo"), 3);
        level.setBlock(base.offset(2, 0, 2), track("zo"), 3);
        helper.assertTrue(Joints.joiner().cost(level, base, base.offset(2, 0, 2)).isEmpty(), "too sharp to lay");
        helper.assertTrue(level.getBlockEntity(base) == null && level.getBlockEntity(base.offset(2, 0, 2)) == null,
            "and asking lays nothing");
        helper.succeed();
    }

    static Worker worker(GameTestHelper helper, BlockPos feet, int tracks) {
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        SimpleContainer pack = new SimpleContainer(27);
        for (ItemStack stack : of(track(), tracks)) {
            pack.addItem(stack);
        }
        return new Worker() {
            public Resident resident() {
                return new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
            }

            public Mob body() {
                return body;
            }

            public Container pack() {
                return pack;
            }

            public List<Store> within() {
                return List.of();
            }

            public ItemStack held(ToolNeed need) {
                return ItemStack.EMPTY;
            }

            public void spill(ItemStack stack) {
            }

            public int rankOf(String perk) {
                return 0;
            }

            public List<Node> route() {
                return List.of();
            }

            public Placement placement() {
                return Placement.NOWHERE;
            }
        };
    }

    static long tracks(Worker worker) {
        return Goods.countIn(worker.pack(), ItemSpec.of(ResourceLocation.fromNamespaceAndPath("create", "track")));
    }

    // Does every piece the site has out, tells it each is done, and lets it hear so.
    static void work(GameTestHelper helper, SiteRig rig, Worker worker) {
        for (BuildSite.Piece piece : rig.site.pieces()) {
            helper.assertTrue(Commit.run(helper.getLevel(), piece.node(), worker) instanceof Outcome.Done,
                "work asked for can be done");
            rig.end(piece.node(), Ending.DONE);
        }
        rig.site.tick(helper.getLevel());
    }

    @GameTest(template = "empty_large", timeoutTicks = 200)
    public static void residentsLayBothEndsThenPayCreateForTheTurnBetweenThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 3));
        floor(level, base, 12);
        BlockPos from = base;
        BlockPos to = base.offset(8, 0, 8);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        cells.put(from, track("xo"));
        cells.put(to, track("zo"));
        BuildTarget target = new BuildTarget(WorldPos.of(level, from).realm(), cells, Set.of(),
            List.of(new Joint(from, to)), BlockPos.ZERO);
        Mob walker = EntityType.PIG.create(level);
        walker.moveTo(base.getX() + 4.5D, base.getY(), base.getZ() + 4.5D);
        Worker worker = worker(helper, base.offset(4, 0, 4), 64);

        SiteRig site = SiteRig.start(level, target);
        helper.assertTrue(site.site.pieces().stream().allMatch(piece -> piece.work().step().orElseThrow().kind()
            == BuildJob.Kind.LAY), "the ends are laid before anything joins them");
        work(helper, site, worker);
        helper.assertValueEqual(tracks(worker), 62L, "one track for each end");
        helper.assertTrue(!BuildPlanner.complete(level, target), "two loose ends are not the railway yet");

        List<BuildSite.Piece> joins = site.site.pieces();
        helper.assertValueEqual(joins.size(), 1, "one join is asked for once both ends stand");
        Node join = joins.getFirst().node();
        int owed = count(Joints.joiner().cost(level, from, to).orElseThrow(), track());
        helper.assertValueEqual(join.spec().needs(), List.of(new Need(ItemSpec.of(
            ResourceLocation.fromNamespaceAndPath("create", "track")), owed)), "the join asks for what Create charges");
        work(helper, site, worker);
        helper.assertValueEqual(tracks(worker), 62L - owed, "and spends exactly that");
        helper.assertTrue(Joints.joiner().joined(level, from, to), "the turn is laid");
        helper.assertTrue(BuildPlanner.complete(level, target), "the railway is complete");
        helper.assertTrue(site.site.complete(), "and its order is done");

        Map<BlockPos, BlockState> cleared = new LinkedHashMap<>();
        cleared.put(from, Blocks.AIR.defaultBlockState());
        SiteRig clearing = SiteRig.start(level, new BuildTarget(WorldPos.of(level, from).realm(), cleared));
        long before = tracks(worker);
        List<ItemStack> made = new ArrayList<>();
        for (BuildSite.Piece piece : clearing.site.pieces()) {
            if (Commit.run(level, piece.node(), worker) instanceof Outcome.Done done) {
                made.addAll(done.made());
            }
        }
        helper.assertValueEqual(count(made, track()), owed + 1, "taking an end up gives back it and the whole turn");
        helper.assertTrue(!Joints.joiner().joined(level, from, to), "the turn is gone with its end");
        helper.assertValueEqual(tracks(worker), before, "nothing is taken from the worker to take it up");
        helper.runAfterDelay(5, () -> {
            helper.assertValueEqual(level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(12)).size(), 0,
                "nothing spills on the ground");
            helper.succeed();
        });
    }
}
