package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
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
    public static void aHerdAtItsTargetStillGetsOneBreedingPair(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 2;
        helper.assertTrue(rig.nodes().size() == 1, "a herd exactly at its target still breeds for meat");
        helper.assertTrue(rig.nodes(Herd.Job.CULL).isEmpty(), "the retained adults are not surplus");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "pasture_twins_current_settings")
    public static void aFeedingPlannedBeforeATargetEditCapsItsRealTwinBirthAtTheCurrentTarget(GameTestHelper helper) {
        Effects twins = Effects.of(List.of(new Effects.Part("certain_twins", Modifier.add(PastureContent.TWINS, 1))));
        for (int currentTarget : List.of(3, 4)) {
            Rig rig = pair(helper);
            try {
                rig.target = 8;
                Node feed = rig.nodes().getFirst();
                rig.target = currentTarget;
                SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
                helper.assertTrue(Commit.run(rig.level, feed, rig.worker(feed, pack, twins)) instanceof Outcome.Done,
                    "lowering the target still leaves this ordinary breeding pair legitimate");
                helper.assertValueEqual(pack.countItem(Items.WHEAT), 0, "the real transaction consumes both wheat");
                helper.assertTrue(rig.animals.stream().allMatch(Cow::isInLove), "both actual parents enter love");
                Cow first = rig.animals.getFirst();
                Cow second = rig.animals.getLast();
                var ordinary = first.getBreedOffspring(rig.level, second);
                helper.assertTrue(ordinary != null, "vanilla creates the ordinary offspring for the real birth event");
                ordinary.setAge(-24000);
                ordinary.setPos(first.position());
                BreedingPerks.born(new BabyEntitySpawnEvent(first, second, ordinary));
                int bonus = currentTarget - 3;
                helper.assertValueEqual(rig.pen().stock(rig.level).size(), 2 + bonus,
                    "the callback adds a real twin only when both children fit the edited target");
                helper.assertTrue(rig.level.addFreshEntity(ordinary), "the ordinary child then joins the actual world");
                List<Animal> born = rig.pen().stock(rig.level);
                helper.assertValueEqual(born.size(), currentTarget,
                    "the old target of eight cannot authorize an extra animal beyond the new target");
                helper.assertTrue(born.stream().filter(Animal::isBaby).count() == 1L + bonus,
                    "the target-four control proves the certain twin perk really spawned its extra juvenile");
            } finally {
                rig.pen().stock(rig.level).forEach(Animal::discard);
                rig.clear();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSurplusCalfMustGrowBeforeTheAdultReserveCanBeCulled(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 2;
        rig.cow(4, 3, -24000);
        helper.assertTrue(rig.nodes(Herd.Job.CULL).isEmpty(), "a calf must not cost the herd a retained adult");
        helper.assertTrue(rig.nodes().isEmpty(), "wait for the surplus calf rather than breeding more");
        rig.animals.getLast().setAge(0);
        List<Node> culls = rig.nodes(Herd.Job.CULL);
        helper.assertTrue(culls.size() == 1, "three adults against a target of two provide one carcass");
        helper.assertTrue(Commit.run(rig.level, culls.getFirst(), rig.worker(culls.getFirst(), new SimpleContainer(4)))
            instanceof Outcome.Done, "the surplus adult is culled");
        helper.assertTrue(rig.animals.stream().filter(Cow::isAlive).count() == 2, "two adult breeders remain");
        helper.assertTrue(rig.nodes().size() == 1, "the retained pair can start the next cycle");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anInLovePairReservesTheOnlySurplusBirth(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 4;
        rig.cow(4, 3, 0);
        rig.cow(5, 3, 0);
        List<Node> first = rig.nodes();
        helper.assertTrue(first.size() == 1, "only one pair starts a surplus birth at the target");
        Node feed = first.getFirst();
        helper.assertTrue(Commit.run(rig.level, feed,
            rig.worker(feed, new SimpleContainer(new ItemStack(Items.WHEAT, 2)))) instanceof Outcome.Done,
            "the first pair enters love");
        helper.assertTrue(rig.animals.stream().filter(Cow::isInLove).count() == 2, "one birth is already pending");
        helper.assertTrue(rig.nodes().isEmpty(), "the other ready pair waits while a birth is pending");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStaleFeedingReturnsItsFoodWhenTheHerdGainsASurplus(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 3;
        Node feed = rig.nodes().getFirst();
        rig.cow(4, 3, -24000);
        rig.cow(5, 3, -24000);
        refusedWithoutFood(helper, rig, feed);
    }

    @GameTest(template = "empty")
    public static void aStaleCullCannotSpendAnAdultThatHasLeftTheHerd(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 2;
        rig.cow(4, 3, 0);
        Node cull = rig.nodes(Herd.Job.CULL).getFirst();
        Cow other = partner(rig, cull);
        other.discard();
        Cow subject = (Cow) rig.level.getEntity(((WorkSite.AtEntity) cull.spec().site()).entity());
        float before = subject.getHealth();
        helper.assertTrue(Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4))) instanceof Outcome.Failed,
            "a planned surplus is rechecked after another adult disappears");
        helper.assertTrue(subject.isAlive() && subject.getHealth() == before, "the retained animal takes no damage");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void currentSettingsAndZoneRemovalStopOldCullJobs(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 1;
        Node cull = rig.nodes(Herd.Job.CULL).getFirst();
        rig.target = 2;
        helper.assertTrue(Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4))) instanceof Outcome.Failed,
            "raising the current target protects the herd before the next survey");
        rig.target = 0;
        cull = rig.nodes(Herd.Job.CULL).getFirst();
        rig.registered = false;
        helper.assertTrue(Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4))) instanceof Outcome.Failed,
            "removing the pasture also cancels an already planned cull");
        helper.assertTrue(rig.animals.stream().allMatch(Cow::isAlive), "both adults survive the obsolete work");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void zeroClearsAdultsAndOneCannotCreateABreedingPair(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 0;
        rig.cow(4, 3, -24000);
        helper.assertTrue(rig.nodes().isEmpty(), "zero disables breeding");
        List<Node> culls = rig.nodes(Herd.Job.CULL);
        helper.assertTrue(culls.size() == 2, "zero clears the adults, but never slaughters the calf");
        for (Node cull : culls) {
            helper.assertTrue(Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4))) instanceof Outcome.Done,
                "each surplus adult is removed exactly once");
        }
        helper.assertTrue(rig.animals.getLast().isAlive(), "the calf remains until grown");
        rig.clear();
        rig = new Rig(helper);
        rig.target = 1;
        rig.cow(2, 3, 0);
        helper.assertTrue(rig.nodes().isEmpty() && rig.nodes(Herd.Job.CULL).isEmpty(),
            "one retained adult is neither fed alone nor culled");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlannedCullCannotKillABabyOrAnAnimalOutsideTheCurrentPen(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 2;
        rig.cow(4, 3, 0);
        Node cull = rig.nodes(Herd.Job.CULL).getFirst();
        Cow subject = (Cow) rig.level.getEntity(((WorkSite.AtEntity) cull.spec().site()).entity());
        subject.setAge(-24000);
        helper.assertTrue(Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4))) instanceof Outcome.Failed,
            "a subject which became a baby is not killed");
        subject.setAge(0);
        subject.setPos(Vec3.atBottomCenterOf(rig.floor.offset(-2, 1, 3)));
        helper.assertTrue(Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4))) instanceof Outcome.Failed,
            "an animal outside the current pasture is not killed");
        helper.assertTrue(rig.animals.stream().allMatch(Cow::isAlive), "obsolete work harms no animal");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void parallelFeedingCommitsRecheckCurrentTargetAndPendingBirths(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 6;
        rig.cow(4, 3, 0);
        rig.cow(5, 3, 0);
        List<Node> planned = rig.nodes();
        helper.assertTrue(planned.size() == 2, "two missing head initially allow two pairs");
        rig.target = 4;
        Node first = planned.getFirst();
        helper.assertTrue(Commit.run(rig.level, first,
            rig.worker(first, new SimpleContainer(new ItemStack(Items.WHEAT, 2)))) instanceof Outcome.Done,
            "one surplus birth is still allowed at the new target");
        Node second = planned.getLast();
        SimpleContainer food = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(Commit.run(rig.level, second, rig.worker(second, food)) instanceof Outcome.Failed,
            "a second stale task cannot reserve the same surplus birth");
        helper.assertTrue(food.countItem(Items.WHEAT) == 2 && rig.animals.stream().filter(Cow::isInLove).count() == 2,
            "the rejected pair keeps its food and no extra parents enter love");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void parallelCullsNeverCrossTheCurrentAdultReserve(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 1;
        rig.cow(4, 3, 0);
        rig.cow(5, 3, 0);
        List<Node> culls = rig.nodes(Herd.Job.CULL);
        helper.assertTrue(culls.size() == 3, "three surplus adults are initially planned");
        rig.target = 2;
        for (int index = 0; index < culls.size(); index++) {
            Node cull = culls.get(index);
            Outcome result = Commit.run(rig.level, cull, rig.worker(cull, new SimpleContainer(4)));
            helper.assertTrue(index < 2 ? result instanceof Outcome.Done : result instanceof Outcome.Failed,
                "each commit reads the remaining adult reserve, including other workers' removals");
        }
        helper.assertTrue(rig.animals.stream().filter(Cow::isAlive).count() == 2, "the new target retains both adults");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyLivestockBreedsAtTargetAndWaitsForMatureSurplus(GameTestHelper helper) {
        for (EntityType<? extends Animal> type : List.<EntityType<? extends Animal>>of(
                EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN)) {
            Rig rig = new Rig(helper);
            rig.target = 2;
            rig.keeps = Livestock.named(BuiltInRegistries.ENTITY_TYPE.getKey(type)).orElseThrow();
            List<Animal> stock = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                Animal animal = type.create(rig.level);
                animal.setUUID(new UUID(rig.zone.id().getMostSignificantBits(), index + 1L));
                animal.setPos(Vec3.atBottomCenterOf(rig.floor.offset(index + 2, 1, 3)));
                animal.setAge(index == 2 ? -24000 : 0);
                if (index < 2) {
                    rig.level.addFreshEntity(animal);
                }
                stock.add(animal);
            }
            helper.assertTrue(rig.nodes().size() == 1, type + ": two retained adults still receive pair feeding");
            rig.level.addFreshEntity(stock.getLast());
            helper.assertTrue(rig.nodes().isEmpty() && rig.nodes(Herd.Job.CULL).isEmpty(),
                type + ": wait for a surplus juvenile without killing breeders");
            stock.getLast().setAge(0);
            helper.assertTrue(rig.nodes(Herd.Job.CULL).size() == 1, type + ": exactly one mature animal is surplus");
            stock.forEach(Animal::discard);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void theAtTargetCycleProducesARealCalfAndCanStartAgain(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 2;
        List<Node> feeding = rig.nodes();
        helper.assertTrue(feeding.size() == 1, "the pair at target receives breeding work");
        Node feed = feeding.getFirst();
        helper.assertTrue(Commit.run(rig.level, feed,
            rig.worker(feed, new SimpleContainer(new ItemStack(Items.WHEAT, 2)))) instanceof Outcome.Done,
            "the retained pair is fed");
        helper.succeedWhen(() -> {
            var stock = rig.level.getEntitiesOfClass(Cow.class, rig.pen().reach(rig.level),
                cow -> cow.isAlive() && rig.pen().holds(cow));
            Cow calf = stock.stream().filter(Cow::isBaby).findFirst().orElse(null);
            helper.assertTrue(calf != null, "vanilla breeding produces a real calf at the target");
            helper.assertTrue(rig.nodes(Herd.Job.CULL).isEmpty(), "the parents survive while the calf grows");
            calf.setAge(0);
            List<Node> culls = rig.nodes(Herd.Job.CULL);
            helper.assertTrue(culls.size() == 1, "one adult becomes surplus after the calf grows");
            helper.assertTrue(Commit.run(rig.level, culls.getFirst(), rig.worker(culls.getFirst(), new SimpleContainer(4)))
                instanceof Outcome.Done, "the mature surplus is collected");
            stock.stream().filter(Cow::isAlive).forEach(cow -> cow.setAge(0));
            helper.assertTrue(rig.nodes().size() == 1, "once the normal cooldown ends, the next cycle is available");
            stock.forEach(Cow::discard);
        });
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
        int target = 8;
        boolean registered = true;
        Livestock keeps = Livestock.COW;

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
                    case "choice" -> keeps.type();
                    case "count" -> target;
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
                    case "zonesIn" -> registered ? List.of(zone) : List.of();
                    default -> throw new AssertionError(method.getName());
                });
        }

        List<Node> nodes() {
            return nodes(Herd.Job.FEED);
        }

        List<Node> nodes(Herd.Job job) {
            herd.begin();
            Herd.Tally tally = herd.reconcile(view(), front(), new ArrayList<>()).getFirst();
            PenChores chores = new PenChores(tally.pen(), job, () -> { }, made,
                level -> Pen.find(view(), front(), zone.id()));
            chores.settle(tally.pen(), tally.work().getOrDefault(job, List.of()));
            List<Grown> goals = new ArrayList<>();
            chores.goals(level, goals);
            return goals.stream().flatMap(goal -> goal.nodes().stream()).toList();
        }

        Worker worker(Node node, SimpleContainer pack) {
            return worker(node, pack, Effects.NONE);
        }

        Worker worker(Node node, SimpleContainer pack, Effects effects) {
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
                    case "effects" -> effects;
                    default -> throw new AssertionError(method.getName());
                });
        }

        void clear() {
            animals.forEach(Cow::discard);
        }
    }
}
