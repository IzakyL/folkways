package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PastureBreedingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PastureBreedingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void oneReadyAdultAndOneCoolingDownNeedNoFood(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.cow(2, 3, 0);
        rig.cow(3, 3, 6000);
        helper.assertTrue(rig.nodes().isEmpty(), "a cooling-down adult is not a breeding partner");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLoneAdultOrABabyDoesNotReceiveBreedingFood(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.cow(2, 3, 0);
        helper.assertTrue(rig.nodes().isEmpty(), "a lone adult has no partner");
        rig.cow(3, 3, -24000);
        helper.assertTrue(rig.nodes().isEmpty(), "a baby cannot be its partner");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aReadyPairIsOneChoreWithFoodForBoth(GameTestHelper helper) {
        Rig rig = pair(helper);
        List<Node> nodes = rig.nodes();
        helper.assertTrue(nodes.size() == 1, "two adults are one feeding chore, got " + nodes.size());
        helper.assertTrue(nodes.getFirst().spec().needs().stream().mapToLong(need -> need.count()).sum() == 2,
            "the chore brings food for both animals");
        rig.cow(4, 3, 0);
        helper.assertTrue(rig.nodes().size() == 1, "an odd animal is not fed alone");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bothAnimalsEnterLoveOnTheSameCommit(GameTestHelper helper) {
        Rig rig = pair(helper);
        Node node = rig.nodes().getFirst();
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker(node, pack)) instanceof Outcome.Done,
            "the pair feeding succeeds");
        helper.assertTrue(rig.animals.stream().allMatch(Cow::isInLove), "both animals enter love together");
        helper.assertTrue(pack.countItem(Items.WHEAT) == 0, "exactly two wheat are spent");
        helper.assertTrue(rig.nodes().isEmpty(), "a pair already in love needs no more food");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPartnerCoolingDownAfterPlanningReturnsAllFood(GameTestHelper helper) {
        Rig rig = pair(helper);
        Node node = rig.nodes().getFirst();
        partner(rig, node).setAge(6000);
        refusedWithoutFood(helper, rig, node);
    }

    @GameTest(template = "empty")
    public static void aPartnerLeavingThePenAfterPlanningReturnsAllFood(GameTestHelper helper) {
        Rig rig = pair(helper);
        Node node = rig.nodes().getFirst();
        partner(rig, node).setPos(Vec3.atBottomCenterOf(rig.floor.offset(-2, 1, 3)));
        refusedWithoutFood(helper, rig, node);
    }

    @GameTest(template = "empty")
    public static void aMissingPartnerReturnsAllFood(GameTestHelper helper) {
        Rig rig = pair(helper);
        Node node = rig.nodes().getFirst();
        partner(rig, node).discard();
        refusedWithoutFood(helper, rig, node);
    }

    @GameTest(template = "empty")
    public static void widelySeparatedAnimalsAreNotFedAsAPair(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.cow(1, 3, 0);
        rig.cow(11, 3, 0);
        helper.assertTrue(rig.nodes().isEmpty(), "animals with no shared workplace need to come closer first");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneFoodCannotFeedHalfOfAPair(GameTestHelper helper) {
        Rig rig = pair(helper);
        Node node = rig.nodes().getFirst();
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 1));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker(node, pack)) instanceof Outcome.Failed,
            "food for only one animal cannot start a pair's feeding");
        helper.assertTrue(pack.countItem(Items.WHEAT) == 1 && rig.animals.stream().noneMatch(Cow::isInLove),
            "the single wheat is kept and neither animal is fed");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingAPartnerChangesTheFeedingChore(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.cow(4, 3, 6000);
        Node old = rig.nodes().getFirst();
        rig.animals.get(1).setAge(6000);
        rig.animals.get(2).setAge(0);
        Node replacement = rig.nodes().getFirst();
        helper.assertFalse(old.id().equals(replacement.id()), "a different partner is different work");
        helper.assertFalse(old.ready(rig.level), "the stale pair is no longer ready");
        helper.assertTrue(replacement.ready(rig.level), "the replacement pair is ready");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void redrawingAPenRecomputesItsCachedWorkplaces(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.nodes();
        rig.cells.removeIf(cell -> cell.getX() < rig.floor.getX() + 2 || cell.getX() > rig.floor.getX() + 4
            || cell.getZ() < rig.floor.getZ() + 2 || cell.getZ() > rig.floor.getZ() + 4);
        Node updated = rig.nodes().getFirst();
        helper.assertTrue(updated.spec().stances().cells().stream().allMatch(cell -> rig.cells.contains(cell.cell().below())),
            "cached workplaces outside a redrawn pasture are discarded");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pastureWorkCannotFinishInAnOpenGateOrOutsideThePen(GameTestHelper helper) {
        Rig rig = pair(helper);
        BlockPos gate = rig.floor.offset(0, 1, 3);
        rig.level.setBlockAndUpdate(gate, Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.FACING, net.minecraft.core.Direction.EAST)
            .setValue(FenceGateBlock.OPEN, true));
        Node node = rig.nodes().getFirst();
        for (WorldPos cell : node.spec().stances().cells()) {
            helper.assertTrue(rig.cells.contains(cell.cell().below()), "the workplace is inside the pasture: " + cell);
            helper.assertTrue(Math.abs(cell.cell().getX() - gate.getX()) > 1
                    || Math.abs(cell.cell().getZ() - gate.getZ()) > 1,
                "a workplace must leave the doorway clear even with the arrival tolerance: " + cell);
        }
        var resident = PersonBody.RESIDENT.get().create(rig.level);
        resident.setPos(Vec3.atBottomCenterOf(gate).add(0.1D, 0.0D, 0.0D));
        helper.assertFalse(Reach.standingIn(resident, node.spec().stances()),
            "a resident just inside the gate has not reached the workplace");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void aPairFedByOneResidentActuallyProducesACalf(GameTestHelper helper) {
        Rig rig = pair(helper);
        Node node = rig.nodes().getFirst();
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker(node, pack)) instanceof Outcome.Done,
            "feeding the pair succeeds");
        helper.assertTrue(rig.animals.stream().allMatch(Cow::isInLove), "both parents are in love");
        helper.succeedWhen(() -> {
            var herd = rig.level.getEntitiesOfClass(Cow.class, new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(rig.floor),
                Vec3.atLowerCornerOf(rig.floor.offset(13, 5, 7))));
            helper.assertTrue(herd.stream().anyMatch(Cow::isBaby), "vanilla breeding produces a calf");
            herd.forEach(Cow::discard);
        });
    }

    @GameTest(template = "empty")
    public static void aClosedFenceIsOnePenOfTheGroundInsideIt(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Enclosure pen = rig.survey();
        helper.assertTrue(pen.fenced(), "a closed ring of fence is one pen: " + pen.shape());
        helper.assertTrue(pen.inside().size() == 11 * 5, "the pen is the ground inside the fence: " + pen.inside().size());
        helper.assertFalse(pen.small(), "eleven by five is room enough to work among the animals");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGapInTheFenceLeavesThePenOpen(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.level.setBlockAndUpdate(rig.floor.offset(4, 1, 0), Blocks.AIR.defaultBlockState());
        helper.assertTrue(rig.survey().shape() == Enclosure.Shape.OPEN, "a gap lets the ground run out of the pen");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void groundMarkedOutBeyondTheFenceIsNotFenced(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        for (int x = -1; x <= 13; x++) {
            rig.cells.add(rig.floor.offset(x, 0, -1));
        }
        helper.assertTrue(rig.survey().shape() == Enclosure.Shape.OPEN,
            "marked ground outside the fence runs off what was marked out");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGateOpenOrShutStillClosesTheFence(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        BlockPos gate = rig.floor.offset(0, 1, 3);
        for (boolean open : new boolean[] {false, true}) {
            rig.level.setBlockAndUpdate(gate, Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(FenceGateBlock.OPEN, open));
            helper.assertTrue(rig.survey().fenced(), "a gate is part of the fence, open: " + open);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFenceAcrossTheMarkedGroundSplitsIt(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        for (int z = 1; z < 6; z++) {
            rig.level.setBlockAndUpdate(rig.floor.offset(6, 1, z), Blocks.OAK_FENCE.defaultBlockState());
        }
        helper.assertTrue(rig.survey().shape() == Enclosure.Shape.SPLIT, "two pens marked out as one are split");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSmallPenIsWorkedFromOutsideTheFence(GameTestHelper helper) {
        Rig rig = new Rig(helper, 5, 4);
        rig.cow(1, 1, 0);
        rig.cow(2, 1, 0);
        helper.assertTrue(rig.survey().small(), "four by three is too small to work among the animals");
        Node node = rig.nodes().getFirst();
        for (WorldPos cell : node.spec().stances().cells()) {
            BlockPos at = cell.cell().subtract(rig.floor);
            helper.assertTrue(at.getX() < 0 || at.getX() > 5 || at.getZ() < 0 || at.getZ() > 4,
                "the keeper stands outside the fence: " + at);
        }
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker(node, pack)) instanceof Outcome.Done,
            "the pair is fed over the fence");
        helper.assertTrue(rig.animals.stream().allMatch(Cow::isInLove), "both animals are fed");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBigPenIsWorkedFromInsideIt(GameTestHelper helper) {
        Rig rig = pair(helper);
        for (WorldPos cell : rig.nodes().getFirst().spec().stances().cells()) {
            helper.assertTrue(rig.survey().inside().contains(cell.cell()), "the keeper stands inside the pen: " + cell);
        }
        rig.clear();
        helper.succeed();
    }

    private static Rig pair(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.cow(2, 3, 0);
        rig.cow(3, 3, 0);
        return rig;
    }

    private static Cow partner(Rig rig, Node node) {
        UUID first = ((WorkSite.AtEntity) node.spec().site()).entity();
        return rig.animals.stream().filter(cow -> !cow.getUUID().equals(first)).findFirst().orElseThrow();
    }

    private static void refusedWithoutFood(GameTestHelper helper, Rig rig, Node node) {
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(Commit.run(rig.level, node, rig.worker(node, pack)) instanceof Outcome.Failed,
            "an invalid pair is refused at commit time");
        helper.assertTrue(pack.countItem(Items.WHEAT) == 2, "both wheat are returned by the core transaction");
        helper.assertTrue(rig.animals.stream().noneMatch(Cow::isInLove), "neither animal is fed alone");
        rig.clear();
        helper.succeed();
    }

    private static final class Rig {
        final ServerLevel level;
        final BlockPos floor;
        final Set<BlockPos> cells = new LinkedHashSet<>();
        final List<Cow> animals = new ArrayList<>();
        final PastDay made = new PastDay();
        final Herd herd = new Herd(() -> { }, made);
        final ZoneView zone;

        Rig(GameTestHelper helper) {
            this(helper, 12, 6);
        }

        // A fenced pen, its fence on the edge of the ground from 0 to wide and deep, and a block of ground beyond it.
        Rig(GameTestHelper helper, int wide, int deep) {
            level = helper.getLevel();
            floor = helper.absolutePos(new BlockPos(1, 0, 1));
            UUID zoneId = UUID.randomUUID();
            for (int x = -1; x <= wide + 1; x++) {
                for (int z = -1; z <= deep + 1; z++) {
                    BlockPos at = floor.offset(x, 0, z);
                    boolean ring = (x == 0 || x == wide) && z >= 0 && z <= deep
                        || (z == 0 || z == deep) && x >= 0 && x <= wide;
                    level.setBlockAndUpdate(at, Blocks.GRASS_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(at.above(), (ring ? Blocks.OAK_FENCE : Blocks.AIR).defaultBlockState());
                    level.setBlockAndUpdate(at.above(2), Blocks.AIR.defaultBlockState());
                    if (x > 0 && x < wide && z > 0 && z < deep) {
                        cells.add(at);
                    }
                }
            }
            Settings settings = (Settings) Proxy.newProxyInstance(Settings.class.getClassLoader(),
                new Class<?>[] {Settings.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "choice" -> Livestock.COW.type();
                    case "count" -> 8;
                    case "flag" -> false;
                    default -> throw new AssertionError(method.getName());
                });
            zone = (ZoneView) Proxy.newProxyInstance(ZoneView.class.getClassLoader(),
                new Class<?>[] {ZoneView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> zoneId;
                    case "delegation" -> PastureContent.PASTURE;
                    case "dimension" -> level.dimension();
                    case "at" -> WorldPos.of(level, (BlockPos) args[0]);
                    case "cells" -> cells;
                    case "settings" -> settings;
                    default -> throw new AssertionError(method.getName());
                });
        }

        void cow(int x, int z, int age) {
            Cow cow = EntityType.COW.create(level);
            // Unique across concurrently running tests, with stable order within this herd.
            cow.setUUID(new UUID(zone.id().getMostSignificantBits(), animals.size() + 1L));
            cow.setPos(Vec3.atBottomCenterOf(floor.offset(x, 1, z)));
            cow.setAge(age);
            level.addFreshEntity(cow);
            animals.add(cow);
        }

        Enclosure survey() {
            return Enclosure.survey(level, pen());
        }

        Pen pen() {
            return Pen.markedOut(view(), front()).getFirst();
        }

        private ColonyView view() {
            return (ColonyView) Proxy.newProxyInstance(ColonyView.class.getClassLoader(),
                new Class<?>[] {ColonyView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "level" -> level;
                    default -> throw new AssertionError(method.getName());
                });
        }

        private FrontView front() {
            return (FrontView) Proxy.newProxyInstance(FrontView.class.getClassLoader(),
                new Class<?>[] {FrontView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "zonesIn" -> List.of(zone);
                    default -> throw new AssertionError(method.getName());
                });
        }

        List<Node> nodes() {
            herd.begin();
            Herd.Tally tally = herd.reconcile(view(), front(), new ArrayList<>()).getFirst();
            PenChores chores = new PenChores(tally.pen(), Herd.Job.FEED, () -> { }, made);
            chores.settle(tally.pen(), tally.work().getOrDefault(Herd.Job.FEED, List.of()));
            List<Grown> goals = new ArrayList<>();
            chores.goals(level, goals);
            return goals.stream().flatMap(goal -> goal.nodes().stream()).toList();
        }

        Worker worker(Node node, SimpleContainer pack) {
            var resident = PersonBody.RESIDENT.get().create(level);
            resident.setPos(Vec3.atBottomCenterOf(node.spec().stances().cells().iterator().next().block(level)));
            return (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> resident;
                    case "resident" -> resident.resident();
                    case "pack" -> pack;
                    case "within", "route" -> List.of();
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    case "effects" -> Effects.NONE;
                    default -> throw new AssertionError(method.getName());
                });
        }

        void clear() {
            animals.forEach(Cow::discard);
        }
    }
}
