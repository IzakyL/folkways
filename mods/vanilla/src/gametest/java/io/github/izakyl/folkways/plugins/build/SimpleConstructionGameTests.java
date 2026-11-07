package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.person.walk.Breathing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.github.izakyl.folkways.plugins.build.SiteRig.floor;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SimpleConstructionGameTests {

    private static boolean previous;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SimpleConstructionGameTests.class);
    }

    @BeforeBatch(batch = "simple_construction")
    public static void before(ServerLevel level) {
        previous = setting().get();
        setting().set(true);
    }

    @AfterBatch(batch = "simple_construction")
    public static void after(ServerLevel level) {
        setting().set(previous);
    }

    @GameTest(batch = "simple_construction", template = "empty_large", timeoutTicks = 1200)
    public static void aRealResidentBuildsBeyondReachWithoutEnteringOrTeleporting(GameTestHelper helper) {
        floor(helper);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int y = 1; y <= 10; y++) {
            cells.put(at(helper, 12, y, 10), Blocks.STONE.defaultBlockState());
        }
        Scene scene = new Scene(helper, cells, new BlockPos(5, 1, 10));
        Vec3[] stopped = {null};
        scene.observe = () -> {
            helper.assertTrue(scene.body.getX() < at(helper, 11, 1, 10).getX(),
                "the builder never enters the blueprint's column or its immediate interior");
            if (scene.stays.stream().anyMatch(scene::arrived)) {
                if (stopped[0] == null) {
                    stopped[0] = scene.body.position();
                }
                helper.assertTrue(scene.body.position().distanceTo(stopped[0]) < 0.65D,
                    "the resident remains at the entrance throughout every inner wind-up");
            }
            helper.assertTrue(scene.temporary.isEmpty() && scene.site.pieces().stream().noneMatch(piece ->
                    piece.work().step().filter(step -> step.kind() == BuildJob.Kind.RAISE).isPresent()),
                "neither the timed access scan nor presentation creates scaffolds");
        };
        scene.finish(() -> {
            helper.assertTrue(stopped[0] != null, "arrival was accepted at the closed area's entrance");
            helper.assertTrue(at(helper, 12, 10, 10).distSqr(scene.body.blockPosition()) > 64,
                "the last block is built well beyond ordinary reach");
            helper.assertTrue(scene.body.pack().getItem(0).getCount() == 54, "all ten blocks consumed real materials");
            helper.assertTrue(cells.entrySet().stream().allMatch(cell ->
                scene.level.getBlockState(cell.getKey()).equals(cell.getValue())), "the whole blueprint is built");
        });
    }

    @GameTest(batch = "simple_construction", template = "empty_large", timeoutTicks = 1000)
    public static void undergroundExcavationCompletesFromTheEntrance(GameTestHelper helper) {
        floor(helper);
        for (BlockPos cell : BlockPos.betweenClosed(at(helper, 7, 4, 6), at(helper, 17, 4, 14))) {
            helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int x = 12; x <= 15; x++) {
            BlockPos cell = at(helper, x, 1, 10);
            helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
            cells.put(cell, Blocks.AIR.defaultBlockState());
        }
        Scene scene = new Scene(helper, cells, new BlockPos(5, 1, 10));
        scene.observe = () -> helper.assertTrue(scene.body.getX() < at(helper, 12, 1, 10).getX(),
            "the underground builder works from outside the excavation cells");
        scene.finish(() -> helper.assertTrue(cells.keySet().stream().allMatch(scene.level::isEmptyBlock),
            "the excavation clears every requested cell"));
    }

    @GameTest(batch = "simple_construction", template = "empty_large", timeoutTicks = 1200)
    public static void existingScaffoldsAreRemovedAndTheOrderEnds(GameTestHelper helper) {
        floor(helper);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        List<BlockPos> column = java.util.stream.IntStream.rangeClosed(1, 6)
            .mapToObj(y -> at(helper, 13, y, 11)).toList();
        column.forEach(cell -> helper.getLevel().setBlock(cell, ScaffoldAction.state(), 3));
        temporary.added(column.stream().map(cell -> WorldPos.of(helper.getLevel(), cell)).toList());
        Scene scene = new Scene(helper, Map.of(at(helper, 12, 1, 10), Blocks.STONE.defaultBlockState()),
            new BlockPos(5, 1, 10), temporary);
        scene.finish(() -> {
            helper.assertTrue(temporary.isEmpty(), "all owned scaffolds are released after LOWER work");
            helper.assertTrue(column.stream().allMatch(scene.level::isEmptyBlock), "the physical column is removed");
            helper.assertTrue(scene.body.pack().contents().stream().filter(stack -> stack.is(Items.SCAFFOLDING))
                .mapToInt(ItemStack::getCount).sum() == 6, "cleanup returns the six real scaffold items");
        });
    }

    @GameTest(batch = "simple_construction", template = "empty_large", timeoutTicks = 1000)
    public static void aFloodedEntranceOnlyStartsWorkAtABreathablePosition(GameTestHelper helper) {
        floor(helper);
        // Contain the water so the later planned destinations remain dry while inventory is observed.
        pool(helper);
        for (int x = 9; x <= 10; x++) {
            for (int z = 7; z <= 13; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.getLevel().setBlock(at(helper, x, y, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        Map<BlockPos, BlockState> cells = Map.of(at(helper, 12, 1, 10), Blocks.STONE.defaultBlockState());
        Scene scene = new Scene(helper, cells, new BlockPos(10, 1, 10));
        scene.allowSafeRelocation = true;
        helper.assertFalse(scene.arrived(scene.stays.getFirst()),
            "a resident already in the closure but underwater has not arrived");
        boolean[] crossedWater = {false};
        scene.observe = () -> {
            if (!Breathing.canStay(scene.body, scene.body.blockPosition())) {
                crossedWater[0] = true;
                helper.assertFalse(scene.level.getBlockState(at(helper, 12, 1, 10)).is(Blocks.STONE),
                    "construction cannot start at an unbreathable entrance");
            }
        };
        scene.finish(() -> {
            helper.assertTrue(crossedWater[0], "the real resident started inside the flooded entrance");
            helper.assertTrue(Breathing.canStay(scene.body, scene.body.blockPosition()),
                "work starts in breathable air");
            helper.assertTrue(scene.body.getHealth() == scene.body.getMaxHealth(),
                "the builder suffers no drowning damage");
        });
    }

    @GameTest(batch = "simple_construction", template = "empty_large", timeoutTicks = 500)
    public static void aResidentAlreadyInsideStartsWorkInPlace(GameTestHelper helper) {
        floor(helper);
        Scene scene = new Scene(helper, Map.of(at(helper, 12, 1, 10), Blocks.STONE.defaultBlockState()),
            new BlockPos(10, 1, 10));
        Vec3[] start = {null};
        helper.assertTrue(scene.arrived(scene.stays.getFirst()), "a resident already inside can arrive in place");
        scene.observe = () -> {
            if (scene.body.doing().filter(doing -> doing.what().equals(BuildContent.BUILDING)).isPresent()) {
                if (start[0] == null) {
                    start[0] = scene.body.position();
                }
                helper.assertTrue(scene.body.position().distanceTo(start[0]) < 0.1D,
                    "accepting a job from inside the closure creates neither a walk nor a teleport");
            }
        };
        scene.finish(() -> { });
    }

    @GameTest(batch = "simple_construction", template = "empty_large", timeoutTicks = 1000)
    public static void standingToleranceCannotStartWorkAtAnUnderwaterEntrance(GameTestHelper helper) {
        floor(helper);
        pool(helper);
        BlockPos wet = at(helper, 10, 1, 10);
        helper.getLevel().setBlock(wet, Blocks.WATER.defaultBlockState(), 2);
        helper.getLevel().setBlock(wet.above(), Blocks.WATER.defaultBlockState(), 2);
        // The existing foundation keeps the whole pool inside the work area, including the urge's exit.
        Scene scene = new Scene(helper, Map.of(at(helper, 12, 2, 10), Blocks.STONE.defaultBlockState(),
            at(helper, 8, 0, 10), Blocks.STONE.defaultBlockState()), new BlockPos(10, 1, 10));
        scene.allowSafeRelocation = true;
        scene.body.setPos(Vec3.atBottomCenterOf(wet).add(0, 0, 0.3D));
        scene.previousPosition = scene.body.position();
        Node stay = scene.stays.getFirst();
        helper.assertTrue(WorkStances.standingIn(scene.body, stay.spec().stances()),
            "the planned stances are inside ordinary arrival tolerance");
        helper.assertFalse(Breathing.canStay(scene.body, scene.body.blockPosition()),
            "the resident's own eyes remain underwater");
        helper.assertFalse(scene.arrived(stay), "the Stay does not accept an underwater entrance");
        scene.finish(() -> helper.assertTrue(Breathing.canStay(scene.body, scene.body.blockPosition()),
            "construction completes in breathable air after walking, safe relocation or a breathing urge"));
    }

    @GameTest(template = "empty_large")
    public static void disabledConstructionKeepsOrdinaryArrival(GameTestHelper helper) {
        floor(helper);
        helper.assertFalse(FolkwaysVanillaConfig.simpleConstruction(), "simple construction defaults to disabled");
        SiteRig rig = SiteRig.start(helper.getLevel(), new BuildTarget(
            WorldPos.of(helper.getLevel(), at(helper, 12, 1, 10)).realm(),
            Map.of(at(helper, 12, 1, 10), Blocks.STONE.defaultBlockState())));
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(at(helper, 10, 1, 10)));
        Node stay = rig.asked.getFirst().nodes().getFirst();
        helper.assertTrue(stay.arrived(helper.getLevel(), body, stay.spec().stances(),
                    WorkStances.standingIn(body, stay.spec().stances()))
                == WorkStances.standingIn(body, stay.spec().stances()),
            "disabled construction retains ordinary stance arrival");
        rig.site.close();
        body.discard();
        helper.succeed();
    }

    private static ModConfigSpec.BooleanValue setting() {
        return FolkwaysVanillaConfig.SPEC.getValues().get("blueprints.simpleConstruction");
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static void pool(GameTestHelper helper) {
        for (BlockPos cell : BlockPos.betweenClosed(at(helper, 8, 1, 6), at(helper, 11, 3, 14))) {
            if (cell.getX() == at(helper, 8, 1, 6).getX() || cell.getX() == at(helper, 11, 1, 6).getX()
                    || cell.getZ() == at(helper, 8, 1, 6).getZ()
                    || cell.getZ() == at(helper, 8, 1, 14).getZ()) {
                helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    // The production labor loop runs plans and personal urges together, including breathing interruptions.
    private static final class Scene implements BuildSite.Asks {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ResidentEntity body;
        private final TemporaryScaffolds temporary;
        private final BuildSite site;
        private final Colony colony;
        private final LaborFixture simulation;
        private final List<Node> stays = new ArrayList<>();
        private final Set<UUID> ended = new HashSet<>();
        private final Set<UUID> withdrawn = new HashSet<>();
        private Vec3 previousPosition;
        private float previousWalk;
        private Runnable observe = () -> { };
        private boolean closed;
        private boolean allowSafeRelocation;

        Scene(GameTestHelper helper, Map<BlockPos, BlockState> cells, BlockPos start) {
            this(helper, cells, start, new TemporaryScaffolds(() -> { }));
        }

        Scene(GameTestHelper helper, Map<BlockPos, BlockState> cells, BlockPos start, TemporaryScaffolds temporary) {
            this.helper = helper;
            level = helper.getLevel();
            this.temporary = temporary;
            body = PersonBody.RESIDENT.get().create(level);
            body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(start)));
            body.setOnGround(true);
            body.pack().setItem(0, new ItemStack(Items.STONE, 64));
            body.pack().setItem(1, new ItemStack(Items.IRON_PICKAXE));
            body.pack().setItem(2, new ItemStack(Items.IRON_AXE));
            body.pack().setItem(3, new ItemStack(Items.IRON_SHOVEL));
            level.addFreshEntity(body);
            colony = Colonies.mint(level.getServer());
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            colony.entered(body);
            postponeLeisure();
            previousPosition = body.position();
            previousWalk = body.walkDist;
            simulation = new LaborFixture(colony, level);
            BuildTarget target = new BuildTarget(WorldPos.of(level, cells.keySet().iterator().next()).realm(), cells);
            site = new BuildSite(UUID.randomUUID(), this, target, temporary);
            site.start(level, Set.of());
            Cleanup.on(helper, this::close);
        }

        @SuppressWarnings("unchecked")
        private void postponeLeisure() {
            // Inventory observation may take several ticks before assignment. Keep unrelated idle travel out
            // of the movement assertions while retaining the production breathing urges and labor runner.
            try {
                Class<?> type = Class.forName("io.github.izakyl.folkways.plugins.person.living.LivingPresence");
                Object living = colony.service(LivingContent.ID, type).orElseThrow();
                var activity = type.getDeclaredField("leisure");
                activity.setAccessible(true);
                Object leisure = activity.get(living);
                var retry = leisure.getClass().getDeclaredField("nextTry");
                retry.setAccessible(true);
                ((Map<UUID, Long>) retry.get(leisure)).put(body.id(), Long.MAX_VALUE);
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }

        @Override
        public Optional<Colony> colony() {
            return Optional.of(colony);
        }

        @Override
        public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> heard) {
            stays.addAll(work.nodes());
            colony.submit(BuildContent.ID, dimension, work, (node, how) -> {
                ended.add(node);
                heard.accept(node, how);
            });
        }

        @Override
        public void withdraw(UUID node) {
            withdrawn.add(node);
            colony.withdraw(BuildContent.ID, node);
        }

        private boolean arrived(Node stay) {
            return stay.arrived(level, body, stay.spec().stances(),
                WorkStances.standingIn(body, stay.spec().stances()));
        }

        private void advance() {
            double across = Math.hypot(body.getX() - previousPosition.x, body.getZ() - previousPosition.z);
            boolean walked = body.position().distanceTo(previousPosition) < 1.0D
                && across <= (body.walkDist - previousWalk) / 0.6D + 0.05D;
            helper.assertTrue(walked || allowSafeRelocation && Breathing.canStay(body, body.blockPosition())
                    && Closures.in(level).stream().anyMatch(site -> site.holds(BlockPos.containing(previousPosition))
                        && site.holds(body.blockPosition())),
                "movement is a real step or an allowed relocation within the site to breathable air: "
                    + previousPosition + " -> " + body.position());
            helper.assertTrue(body.getHealth() == body.getMaxHealth(), "the builder never suffers damage");
            previousPosition = body.position();
            previousWalk = body.walkDist;
            site.tick(level);
            observe.run();
            simulation.tick();
            helper.assertFalse(!Breathing.canStay(body, body.blockPosition())
                    && body.doing().filter(doing -> doing.what().equals(BuildContent.BUILDING)).isPresent(),
                "no construction wind-up starts while the resident cannot breathe");
        }

        private void finish(Runnable assertions) {
            helper.onEachTick(this::advance);
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(site.complete()
                    && stays.stream().allMatch(node -> ended.contains(node.id()) || withdrawn.contains(node.id())),
                    "the whole site finishes through real holding dispatch: " + site.refusals()
                        + ", pos=" + body.position() + ", entityTicks=" + body.tickCount
                        + ", pieces=" + site.pieces().size() + ", asked=" + stays.size()
                        + ", ended=" + ended.size() + ", withdrawn=" + withdrawn.size()))
                .thenExecute(assertions).thenSucceed();
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            site.close();
            simulation.close();
            body.discard();
            colony.raze();
        }
    }
}
