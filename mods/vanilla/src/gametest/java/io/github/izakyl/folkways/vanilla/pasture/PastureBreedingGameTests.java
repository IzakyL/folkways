package io.github.izakyl.folkways.vanilla.pasture;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.effect.Modifier;
import io.github.izakyl.folkways.core.front.api.FrontView;
import io.github.izakyl.folkways.core.front.api.PastDay;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
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
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class PastureBreedingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PastureBreedingGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aHerdAtItsTargetStillGetsOneBreedingPair(GameTestHelper helper) {
        Rig rig = pair(helper);
        rig.target = 2;
        helper.assertTrue(rig.nodes().size() == 1, "a herd exactly at its target still breeds for meat");
        helper.assertTrue(rig.nodes(Herd.Job.CULL).isEmpty(), "the retained adults are not surplus");
        rig.clear();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "pasture_twins_current_settings")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    // A pair planned for feeding that stops being a valid pair before the commit is refused, and the core
    // transaction hands both wheat back.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aFeedingInvalidatedAfterPlanningReturnsAllFood(GameTestHelper helper) {
        record Change(String name, int target, BiConsumer<Rig, Node> after) {
        }
        for (Change change : List.of(
                new Change("the herd gains a surplus", 3, (rig, node) -> {
                    rig.cow(4, 3, -24000);
                    rig.cow(5, 3, -24000);
                }),
                new Change("the partner cools down", 8, (rig, node) -> partner(rig, node).setAge(6000)),
                new Change("the partner leaves the pen", 8, (rig, node) ->
                    partner(rig, node).setPos(Vec3.atBottomCenterOf(rig.floor.offset(-2, 1, 3)))),
                new Change("the partner is gone", 8, (rig, node) -> partner(rig, node).discard()))) {
            Rig rig = pair(helper);
            rig.target = change.target();
            Node node = rig.nodes().getFirst();
            change.after().accept(rig, node);
            SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT, 2));
            helper.assertTrue(Commit.run(rig.level, node, rig.worker(node, pack)) instanceof Outcome.Failed,
                "an invalid pair is refused at commit time: " + change.name());
            helper.assertTrue(pack.countItem(Items.WHEAT) == 2,
                "both wheat are returned by the core transaction: " + change.name());
            helper.assertTrue(rig.animals.stream().noneMatch(Cow::isInLove),
                "neither animal is fed alone: " + change.name());
            rig.clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void onlyTameWholeHorsesAreFedAndTheirFoalsAreBornTame(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.target = 4;
        rig.keeps = Livestock.named(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.HORSE)).orElseThrow();
        UUID owner = UUID.randomUUID();
        List<Horse> horses = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Horse horse = EntityType.HORSE.create(rig.level);
            horse.setUUID(new UUID(rig.zone.id().getMostSignificantBits(), index + 1L));
            horse.setPos(Vec3.atBottomCenterOf(rig.floor.offset(index + 2, 1, 3)));
            rig.level.addFreshEntity(horse);
            horses.add(horse);
        }
        try {
            helper.assertTrue(rig.nodes().isEmpty(), "wild horses never breed, so they get no golden carrots");
            horses.forEach(horse -> {
                horse.setTamed(true);
                horse.setOwnerUUID(owner);
            });
            horses.getFirst().setHealth(horses.getFirst().getMaxHealth() - 1.0F);
            helper.assertTrue(rig.nodes().isEmpty(), "a hurt horse cannot breed until it is whole");
            horses.getFirst().setHealth(horses.getFirst().getMaxHealth());
            List<Node> feed = rig.nodes();
            helper.assertTrue(feed.size() == 1, "a tame whole pair is fed");
            SimpleContainer pack = new SimpleContainer(new ItemStack(Items.GOLDEN_CARROT, 2));
            helper.assertTrue(Commit.run(rig.level, feed.getFirst(), rig.worker(feed.getFirst(), pack))
                instanceof Outcome.Done, "the pair takes its golden carrots");
            var foal = horses.getFirst().getBreedOffspring(rig.level, horses.getLast());
            helper.assertTrue(foal instanceof Horse, "two horses make a horse");
            BreedingPerks.born(new BabyEntitySpawnEvent(horses.getFirst(), horses.getLast(), foal));
            Horse born = (Horse) foal;
            helper.assertTrue(born.isTamed(), "a foal bred in the pen is born tame");
            helper.assertValueEqual(born.getOwnerUUID(), owner, "it is tame to its parent's owner");
        } finally {
            horses.forEach(Horse::discard);
            rig.clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 700)
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

    // No pair, no food: a lone adult, an adult beside a baby or a cooling-down partner, or two adults with no
    // shared workplace between them.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void animalsWithoutAReadyPairReceiveNoBreedingFood(GameTestHelper helper) {
        // Each cow is {x, age}, all on one row.
        record Layout(String name, List<int[]> cows) {
        }
        for (Layout layout : List.of(
                new Layout("a lone adult has no partner", List.of(new int[] {2, 0})),
                new Layout("a baby cannot be its partner", List.of(new int[] {2, 0}, new int[] {3, -24000})),
                new Layout("a cooling-down adult is not a breeding partner",
                    List.of(new int[] {2, 0}, new int[] {3, 6000})),
                new Layout("animals with no shared workplace need to come closer first",
                    List.of(new int[] {1, 0}, new int[] {11, 0})))) {
            Rig rig = new Rig(helper);
            layout.cows().forEach(cow -> rig.cow(cow[0], 3, cow[1]));
            helper.assertTrue(rig.nodes().isEmpty(), layout.name());
            rig.clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void pastureWorkCannotFinishInAnOpenGateOrOutsideThePen(GameTestHelper helper) {
        Rig rig = pair(helper);
        BlockPos gate = rig.floor.offset(0, 1, 3);
        gate(rig, true);
        Node node = rig.nodes().getFirst();
        for (WorldPos cell : node.spec().stances().cells()) {
            helper.assertTrue(rig.cells.contains(cell.cell().below()), "the workplace is inside the pasture: " + cell);
            helper.assertTrue(Math.abs(cell.cell().getX() - gate.getX()) > 1
                    || Math.abs(cell.cell().getZ() - gate.getZ()) > 1,
                "a workplace must leave the doorway clear even with the arrival tolerance: " + cell);
        }
        var resident = PersonBody.RESIDENT.get().create(rig.level);
        resident.setPos(Vec3.atBottomCenterOf(gate).add(0.1D, 0.0D, 0.0D));
        helper.assertFalse(WorkStances.standingIn(resident, node.spec().stances()),
            "a resident just inside the gate has not reached the workplace");
        rig.clear();
        helper.succeed();
    }

    // A closed ring of fence, gate open or shut, is one pen of the ground inside it. A gap, ground marked out beyond
    // the fence, or a fence across the marked ground each leave something other than one pen.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void theFenceDecidesWhetherTheMarkedGroundIsOnePen(GameTestHelper helper) {
        Rig closed = new Rig(helper);
        Enclosure pen = closed.survey();
        helper.assertTrue(pen.fenced(), "a closed ring of fence is one pen: " + pen.shape());
        helper.assertTrue(pen.inside().size() == 11 * 5, "the pen is the ground inside the fence: " + pen.inside().size());
        helper.assertFalse(pen.small(), "eleven by five is room enough to work among the animals");
        record Change(String name, Consumer<Rig> change, Enclosure.Shape shape) {
        }
        for (Change change : List.of(
                new Change("a shut gate is part of the fence", rig -> gate(rig, false), Enclosure.Shape.FENCED),
                new Change("an open gate is part of the fence", rig -> gate(rig, true), Enclosure.Shape.FENCED),
                new Change("a gap lets the ground run out of the pen",
                    rig -> rig.level.setBlockAndUpdate(rig.floor.offset(4, 1, 0), Blocks.AIR.defaultBlockState()),
                    Enclosure.Shape.OPEN),
                new Change("marked ground outside the fence runs off what was marked out", rig -> {
                    for (int x = -1; x <= 13; x++) {
                        rig.cells.add(rig.floor.offset(x, 0, -1));
                    }
                }, Enclosure.Shape.OPEN),
                new Change("two pens marked out as one are split", rig -> {
                    for (int z = 1; z < 6; z++) {
                        rig.level.setBlockAndUpdate(rig.floor.offset(6, 1, z), Blocks.OAK_FENCE.defaultBlockState());
                    }
                }, Enclosure.Shape.SPLIT))) {
            Rig rig = new Rig(helper);
            change.change().accept(rig);
            helper.assertTrue(rig.survey().shape() == change.shape(), change.name());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void smallPenWorkplacesFollowARotatedFrame(GameTestHelper helper) {
        Rig rig = new Rig(helper, 5, 4);
        Enclosure enclosure = rig.survey();
        Pen ground = rig.pen();
        Vec3 stored = Vec3.atBottomCenterOf(rig.floor.offset(2, 1, 2));
        ItemEntity egg = new ItemEntity(rig.level, stored.x, stored.y, stored.z, new ItemStack(Items.EGG));
        var expected = ground.workplaces(rig.level, egg, enclosure).orElseThrow();
        UUID id = UUID.randomUUID();
        Vec3 origin = Vec3.atLowerCornerOf(rig.floor.offset(30, 10, 30));
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return rig.level; }
            public BlockPos storageOrigin() { return rig.floor; }
            public Vec3 toWorld(Vec3 local) { return origin.add(-local.z, local.y, local.x); }
            public Vec3 toLocal(Vec3 world) {
                Vec3 delta = world.subtract(origin);
                return new Vec3(delta.z, delta.y, -delta.x);
            }
        };
        Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level level, UUID key) {
                return level == rig.level && key.equals(id) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> containing(Level level, BlockPos cell) {
                return level == rig.level && cell.distSqr(rig.floor) < 400 ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                return entity == egg ? Optional.of(frame) : Optional.empty();
            }
        });
        try {
            egg.setPos(frame.toWorld(stored.subtract(Vec3.atLowerCornerOf(rig.floor))));
            Set<BlockPos> localFloor = ground.floor().stream().map(frame::local).collect(Collectors.toSet());
            Pen aboard = new Pen(ground.zone(), ground.keeps(), ground.target(), ground.shear(),
                new WorldPos(new Realm.Frame(id), frame.local(ground.anchor().cell())), localFloor,
                ground.minY() - rig.floor.getY(), ground.maxY() - rig.floor.getY());
            var actual = aboard.workplaces(rig.level, egg, enclosure).orElseThrow();
            Set<BlockPos> actualStorage = actual.cells().stream().map(cell -> frame.storage(cell.cell()))
                .collect(Collectors.toSet());
            Set<BlockPos> expectedStorage = expected.cells().stream().map(WorldPos::cell)
                .collect(Collectors.toSet());
            helper.assertTrue(actualStorage.equals(expectedStorage),
                "a rotated ship preserves the same clear workplaces over the fence");
        } finally {
            remove.run();
            egg.discard();
            rig.clear();
        }
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

    private static void gate(Rig rig, boolean open) {
        rig.level.setBlockAndUpdate(rig.floor.offset(0, 1, 3), Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.FACING, Direction.EAST)
            .setValue(FenceGateBlock.OPEN, open));
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
            Settings settings = Proxies.of(Settings.class, (proxy, method, args) -> switch (method.getName()) {
                case "choice" -> keeps.type();
                case "count" -> target;
                case "flag" -> false;
                default -> throw new AssertionError(method.getName());
            });
            zone = Proxies.of(ZoneView.class, (proxy, method, args) -> switch (method.getName()) {
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
            return Proxies.of(ColonyView.class, (proxy, method, args) -> switch (method.getName()) {
                case "level" -> level;
                default -> throw new AssertionError(method.getName());
            });
        }

        private FrontView front() {
            return Proxies.of(FrontView.class, (proxy, method, args) -> switch (method.getName()) {
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
            return Proxies.of(Worker.class, (proxy, method, args) -> switch (method.getName()) {
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
